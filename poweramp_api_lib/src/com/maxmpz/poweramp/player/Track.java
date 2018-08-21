package com.maxmpz.poweramp.player;

import static junit.framework.Assert.assertEquals;
import java.nio.ByteBuffer;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import android.net.Uri;
import android.net.Uri.Builder;
import android.text.TextUtils;
import android.util.Log;
import com.maxmpz.poweramp.player.TableDefs;

// NOTE: track can be long-living object.
// Don't put anything which can produce context or other leaks here
// NOTE: ensure Track is read-only, except few special cases, like tag-scanned updates or ratings
// This means, fields should be either private + getter or "public final"
// Any exception from that (e.g. pipelineSerial) should be implemented with special extra care and documented
// REVISIT: check threaded access, esp. to non-final vars
// OPTIONS:
// - expose some base track POJO, and internally extended track?

// NOTE: Track contains MetaTrackInfo fields, but MetaTrackInfo can be also updated with repeat changes (appropriate separate MetaTrackInfo object is sent then)
public abstract class Track extends MetaTrackInfo {
	private static final String TAG = "Track";
	private static final boolean LOG = false;
	private static final boolean DEBUG_CHECKS = true;
	
	public static final int DIRECT_STATE_SIZE = Integer.BYTES * 2; // Sync with Pipeline2/pipeline.c

	// Sent with msg_player_track_changed. Sync with values-player.xml
	public static final int TRACK_CHANGED_SAME_UPDATED = 1;

	// Bitset. Sync with rgg.h
	public static final int PA_RG_TYPE_NONE  = 0;
	public static final int PA_RG_TYPE_ALBUM = 1;
	public static final int PA_RG_TYPE_TRACK = 2;

	// Matches avcodec.h SampleFormat enum.

	public static final int PA_SAMPLE_FMT_NONE     = -1;
	public static final int PA_SAMPLE_FMT_U8       = 0;      ///< unsigned 8 bits
	public static final int PA_SAMPLE_FMT_S16      = 1;      ///< signed 16 bits
	public static final int PA_SAMPLE_FMT_S32      = 2;      ///< signed 32 bits
	public static final int PA_SAMPLE_FMT_FLT      = 3;      ///< float
	public static final int PA_SAMPLE_FMT_DBL      = 4;      ///< double
	public static final int PA_SAMPLE_FMT_U8P      = 5;      ///< unsigned 8 bits, planar
	public static final int PA_SAMPLE_FMT_S16P     = 6;      ///< signed 16 bits, planar
	public static final int PA_SAMPLE_FMT_S32P     = 7;      ///< signed 32 bits, planar
	public static final int PA_SAMPLE_FMT_FLTP     = 8;      ///< float, planar
	public static final int PA_SAMPLE_FMT_DBLP     = 9;      ///< double, planar
	public static final int PA_SAMPLE_FMT_S24      = 10;     // packed 24bit
	public static final int PA_SAMPLE_FMT_S8_24    = 11;     // Android Q8.23

	public static final long AV_CH_FRONT_LEFT =             0x00000001;
	public static final long AV_CH_FRONT_RIGHT =            0x00000002; 
	public static final long AV_CH_LAYOUT_STEREO = AV_CH_FRONT_LEFT | AV_CH_FRONT_RIGHT;

	// Category Related =============================================
	
	/**
	 * NOTE: this is not actual filesUri for SHUFFLE_ALL or some other modes, instead, it's resolved (in RNP) to appropriate category based uri, 
	 * so it can be used for building new track uri.
	 * Actual RNP loaded list is avialble from actualLoadedFilesListUri
	 */
	public final @NonNull Uri filesUri; // THREADING: any. Set once by RNP

	/**
	 * Can match mFilesUri for most cases, except SHUFFLE_ALL
	 */
	public final @NonNull Uri actualLoadedFilesListUri; // THREADING: any. Set once by RNP

	// Following are set once (final or final-like). Set by NPD ==================
	public final int catUriMatch; // THREADING: any. Set once by RNP
	
	/**
	 * Localized current track category name, e.g. Folders, Albums, etc.
	 */
	public final @NonNull String catLabel; // THREADING: any. Set once by RNP
	/**
	 * Small category icon resource id, usually 12x12dp size
	 */
	public final int catMicroIconRes; // THREADING: any. Set once by RNP

	public final int shuffle; // THREADING: any. Set once by RNP
	
	// NOTE: Now playing (category) serial. Changed during reloads, etc. Multiple tracks from same category have same npSerial. 
	// Process-unique. Starts from 1 (thus, 0 means no serial and not allowed in Track)
	public final int npSerial;  // THREADING: any.

	// NOTE: Serial used to identify the given track w/o track reference storage or compare. NOTE: pipelineSerial is strictly pipeline serial, which doesn't
	// change when cue tracks are changed, and changes when we repeating same track
	// This serial is generated by NPD. Will be also updated on e.g. tag edits and similar reloads, but will not be updated on optimized reloads of the same unchanged file
	// Process-unique
	public final int serial; // THREADING: any.  

	
	public final @Nullable String prevCategory;
	public final @Nullable String nextCategory;
	
	/**
	 * true if it's possible to navigate by categories
	 * NOTE: track is regenerated and resent when shuffle mode changes (it also usually changes other track properties, like actualLoadedFilesListUri, npSerial, etc.) 
	 */
	public final boolean isCatNavigable;
	

	// Track Related ==========================================
	
	public final long entryId; // THREADING: any. Set once by RNP. Set only for playlist/queue entries.
	public final long folderId; // THREADING: any. 
	public final long albumId;  // THREADING: any. 
	public final long artistId;  // THREADING: any. 
	public final long albumArtistId; // THREADING: any.
	public final long fileId;  // THREADING: any. 
	public final boolean isCue; // THREADING: any. NOTE: this means track is set as CUE in db. Actual CUE playback status is set via OPEN_IS_CUE flag
	public final int cueOffsetMs;
	public final int fileType;

	// NOTE: needed, as if we get it from NPD, it can return some new/modified value which is wrong for this track. Though, this means track is going to be replaced by that new track soon anyway?
	// Also, npd state can change 1sec in advance, due to bufferings
	public final @NonNull String path;  // THREADING: any. 

	public final int listSize;  // THREADING: any.  
	public final int position; // THREADING: any. 

	private final int durationMS; // NOTE: by default this is TagLib/DB duration for the file, may be updated on playback if differes from decoder. CUE: cue-list parsed duration

	protected int rating; // MUTABLE. THREADING: set by gui and ps/RNP. Assuming leakage is enough for sync REVISIT: avoid setting this from gui

	// NOTE: this is needed so supportsCatNav state is bound to current track, not to NPD, which can change it anytime (e.g. on crossfades, etc)
	//public final boolean supportsCategoryNavigation;

	private final @Nullable String readablePath; // THREADING: any. Set once by RNP. Used for content:// raw files, where track.path is hard-to-read uri

	// Equ stuff - used by PS ===================================
	public final long equPresetId; // THREADING: any. Set once by RNP.
	//public final int equPresetIndex; // THREADING: any. Set once by RNP.
	public final @Nullable String equPresetName; // THREADING: any. Set once by RNP.
	public final @Nullable String equPresetData; // THREADING: any. Set once by RNP.

	// These can be changed after NPD and before publishing track to world by ps ==============
	// REVISIT: can be also made final, if I run TrackProcessor (==TagReader) in NPD before I give track away.
	// I don't do this now, as TagReader is IO operation, which can slow down start of track
	protected int tagStatus; // MUTABLE
	protected @Nullable float[] wave; // MUTABLE
	protected @Nullable String title; // MUTABLE
	protected @Nullable String album; // MUTABLE
	protected @Nullable String artist; // MUTABLE
	protected @Nullable String albumArtist; // MUTABLE
	protected int trackNum; // MUTABLE
	//private String lyricsTag; // REVISIT: not used ATM	

	// TrackInfo =============================================
	// THREADING: write: ps, read: any
	protected int trackSampleRate; // MUTABLE 	
	protected int trackBitsPerSample; // MUTABLE
	//public long trackChannelLayout; // REVISIT: not used ATM
	protected int trackChannels; // MUTABLE
	protected int trackDurationMS; // MUTABLE. NOTE Always duration of source track itself (!= durationMS for cues)
	protected int trackBitRate; // MUTABLE
	protected @Nullable String trackDecoderName; // MUTABLE
	protected @Nullable String trackDecoderUniqName; // MUTABLE
	protected @Nullable String trackCodec; // MUTABLE
	protected @Nullable String trackDecodeInfo; // MUTABLE
	protected boolean trackIsGapless; // MUTABLE
	protected int trackRgType; // MUTABLE
	protected float trackTrackGain; // MUTABLE
	protected float trackTrackPeak; // MUTABLE
	protected float trackAlbumGain; // MUTABLE
	protected float trackAlbumPeak; // MUTABLE

	// TODO: revisit - split play request flags from track "state" permanent flags?
	// NOTE: now these combines 2 types of flags -> "open" flags - which comes from flags to PS playWhatInNp() and to pipeline
	// + flags _re-set_ by PS per playing session each time (e.g. isCue=>FLAG_OPEN_IS_CUE)
	// Thus, this is basically playingSessionFlags
	// NOTE: as track can be repeated, don't assign any flags which signals something about end of playing, as those will be reset on track restart
	// REVISIT: this is modified in thread-unsafe way now
	// NOTE: generally those are set once per track playback by ps when track starts. Can I consider them read only?
	// THREADING: write: ps, read: any
	protected int flags; // MUTABLE
	private @Nullable String mCounterText;


	// NOTE: sync with PowerampAPI.Track.Flags
	public static final int FLAG_ADVANCE_NONE         = 0;
	public static final int FLAG_ADVANCE_FORWARD      = 1;
	public static final int FLAG_ADVANCE_BACKWARD     = 2;
	public static final int FLAG_ADVANCE_FORWARD_CAT  = 3;
	public static final int FLAG_ADVANCE_BACKWARD_CAT = 4;
	public static final int FLAG_ADVANCE_MASK         = 0x0007;

	public static final int FLAG_NOTIFICATION_UI      = 0x0020; // REVISIT: move away? If set, event comes from notification ui and we will animate aa update then

	public final static int FLAG_OPEN_REVERSE         = 0x0100; // REVISIT: move to open flags. Means we're seeking backwards, so playing starts from end (-5s). Used for starting new track
	public final static int FLAG_OPEN_NO_CROSSFADE    = 0x0400; // REVISIT: move to open flags. Used for starting new track
	public final static int FLAG_OPEN_AUTO_ADVANCED   = 0x1000; // REVISIT: move to open flags. Means no fade in/out. Used for starting new track
	public final static int FLAG_OPEN_NO_TOAST        = 0x2000; // REVISIT: move to open flags. Don't show toast for failure - we'll shown own
	public static final int FLAGS_ALLOW_AS_INPUT_MASK = 0xFFFF; // Allow only the above flags as input
	public final static int FLAG_OPEN_IS_CUE          = 0x00010000; // REVISIT: move to open flags.


	// THREADING: ps
	public Track(
			long fileId, long entryId, long folderId, long artistId, long albumArtistId, long albumId, int npSerial, int listSize, int position, 
			@NonNull String path, String readablePath, 
			int catUriMatch, @NonNull String catLabel, int catMicroIconRes, 
			int shuffle, 
			@NonNull String title, String album, String artist, String albumArtist, int durationMS, 
			int trackNum, int rating, 
			int tagStatus, 
			String nextTrackInfo, String prevCategory, String nextCategory,
			boolean isCatNavigable,
			@Nullable float[] wave, 
			int fileType, 
			boolean isCue, int cueOffsetMs, 
			long equPresetId, //int equPresetIndex, 
			String equPresetData, String equPresetName, @NonNull Uri filesUri, @NonNull Uri actualLoadedFilesListUri, 
			int serial
	) {
		super(nextTrackInfo);

		this.fileId = fileId;
		this.entryId = entryId;
		this.folderId = folderId;
		this.artistId = artistId;
		this.albumArtistId = albumArtistId;
		this.albumId = albumId;
		this.npSerial = npSerial;
		this.listSize = listSize;
		this.position = position;
		this.path = path;
		this.readablePath = TextUtils.isEmpty(readablePath) ? null : readablePath; // NOTE: ensure null for empty string
		this.catUriMatch = catUriMatch;
		this.catLabel = catLabel;
		this.catMicroIconRes = catMicroIconRes;

		this.shuffle = shuffle;

		this.nextCategory = nextCategory;
		this.prevCategory = prevCategory;
		this.isCatNavigable = isCatNavigable;


		this.title = title;
		this.album = album;
		this.artist = artist; 
		this.albumArtist = albumArtist;
		this.durationMS = durationMS;
		this.tagStatus = tagStatus;

		this.wave = wave;
		this.fileType = fileType;
		this.isCue = isCue;
		this.cueOffsetMs = cueOffsetMs;
		this.trackNum = trackNum; 
		this.equPresetId = equPresetId;
		//this.equPresetIndex = equPresetIndex;
		this.equPresetData = equPresetData;
		this.equPresetName = equPresetName; 
		this.rating = rating;
		this.filesUri = filesUri;
		this.actualLoadedFilesListUri = actualLoadedFilesListUri;
		this.serial = serial;

		if(DEBUG_CHECKS && title.length() == 0) throw new AssertionError(this); // May happen after update (from db with empty titles in DB)
		if(DEBUG_CHECKS && npSerial == 0) throw new AssertionError(this);
	}

	/**
	 * @return trackinfo (from loaded file properties) duration for non-cues, otherwise returns duration from the db (can be 0 for non-scanned tracks)
	 */
	public int getDurationMS() {
		if(!isCue && trackSampleRate != 0) {
			return trackDurationMS;
		} else {
			return durationMS;
		}
	}

	/**
	 * @param pipelineDirectStateBlob Pipeline directly updated blob buffer
	 * @return current Pipeline playing position if pipeline currently played track matches this track, or -1 on failure 
	 */
	public abstract int getPositionMsFromPipelineBlob(@NonNull ByteBuffer pipelineDirectStateBlob);

	public final boolean isRaw() {
		return fileId == PowerampAPI.RAW_TRACK_ID; 
	}

	/**
	 * @return true, if Track is loaded from our library DB. This is means track is not raw (while technically raw file can be temporarily in the DB as well)
	 */
	public final boolean isFromLibrary() {
		return fileId > PowerampAPI.NO_ID;
	}

	/**
	 * @return durationMS raw field (value from DB/tag scanner)
	 */
	public int getDbDurationMS() { 
		return durationMS;
	}

	public int getDurationS() {
		return (getDurationMS() + 500) / 1000;
	}

	public int getRating() {
		return rating;
	}

	public int getTagStatus() {
		return tagStatus;
	}

	public @Nullable float[] getWave() {
		return wave;
	}

	public int getTrackSampleRate() {
		return trackSampleRate;
	}

	public int getTrackBitsPerSample() {
		return trackBitsPerSample;
	}

	public int getTrackChannels() {
		return trackChannels;
	}

	public int getTrackDurationMS() { // Package. Duration from pipeline
		return trackDurationMS;
	}

	public int getTrackBitRate() {
		return trackBitRate;
	}

	public String getTrackCodec() {
		return trackCodec;
	}

	public boolean isTrackIsGapless() {
		return trackIsGapless;
	}

	public int getTrackRgType() {
		return trackRgType;
	}

	public float getTrackTrackGain() {
		return trackTrackGain;
	}

	public float getTrackTrackPeak() {
		return trackTrackPeak;
	}

	public float getTrackAlbumGain() {
		return trackAlbumGain;
	}

	public float getTrackAlbumPeak() {
		return trackAlbumPeak;
	}

	public @Nullable String getTrackDecoderName() {
		return trackDecoderName;
	}

	public @Nullable String getTrackDecoderUniqName() {
		return trackDecoderUniqName;
	}

	public @Nullable String getTrackDecodeInfo() {
		return trackDecodeInfo;
	}


	public String getTitle() {
		return title;
	}

	public String getAlbum() {
		return album;
	}

	public String getArtist() {
		return artist;
	}

	public String getAlbumArtist() {
		return albumArtist;
	}

	public int getTrackNum() { // REVISIT: not used ATM
		return trackNum;
	}

	/**
	 * @return either fileId or entryId. entryId is for playlists/queue (where main id is entry id due to possible same-file-id duplications)
	 */
	public final long getIdForFilesCursor() {
		return entryId != PowerampAPI.NO_ID ? entryId : fileId;
	}


	// NOTE: shouldn't be called from lists.
	// NOTE: actually, just title is never empty/unknown (DB title and filename should be never empty)
	public final @NonNull String getReadableTitle(@Nullable String unknown, boolean useFilename) {
		return getReadableTitle(title, readablePath, path, unknown, useFilename);
	}

	// NOTE: used by next track info code for data retrieval directly from cursor
	public final static @NonNull String getReadableTitle(@Nullable String title, @Nullable String readablePath, @NonNull String path, @Nullable String unknown, boolean useFilename) {
		if(!useFilename && title != null && title.length() > 0) {
			return title;
		}
		String pathSubstitute = readablePath != null ? readablePath : path;
		if(pathSubstitute.length() > 0) {
			int slash = pathSubstitute.lastIndexOf('/');
			if(slash == -1 || slash == pathSubstitute.length() - 1) {
				return pathSubstitute;
			}
			pathSubstitute = pathSubstitute.substring(slash + 1);
			if(pathSubstitute != null) { // Generally can't be the case, required by @NonNull
				return pathSubstitute;
			}
		}
		if(unknown == null || unknown.length() == 0) {
			return "???"; // Failed everything. Should never happen though
		}
		return unknown;
	}


	public @NonNull String getReadableArtist(@Nullable String unknown) { // TODO: use UNKNOWN_ID for unknown label?
		String str = artist;
		if(artistId != TableDefs.UNKNOWN_ID && str != null && str.length() > 0) {
			return str;
		}
		if(unknown == null || unknown.length() == 0) {
			return "???"; // Failed everything. Should never happen though
		}
		return unknown;
	}

	public @NonNull String getReadableAlbumArtist(@Nullable String unknown) { // TODO: use UNKNOWN_ID for unknown label?
		String str = albumArtist;
		if(albumArtistId != TableDefs.UNKNOWN_ID && str != null && str.length() > 0) {
			return str;
		}
		if(unknown == null || unknown.length() == 0) {
			return "???"; // Failed everything. Should never happen though
		}
		return unknown;
	}

	public @NonNull String getReadableAlbum(@Nullable String unknown, boolean hideUnknown) { 
		if(hideUnknown && albumId == TableDefs.UNKNOWN_ID) {
			return "";
		}
		String str = album;
		if(albumId != TableDefs.UNKNOWN_ID && str != null && str.length() > 0) {
			return str;
		}
		if(unknown == null || unknown.length() == 0) {
			return "???"; // Failed everything. Should never happen though
		}
		return unknown;
	}

	public String getReadableFolderName() {
		if(LOG) Log.w(TAG, "getReadableFolderName path=" + path);

		int lastSlash = path.lastIndexOf('/');
		if(lastSlash <= 0) {
			return "/";
		}

		if(LOG) Log.w(TAG, "getReadableFolderName lastSlash=" + lastSlash);

		int prevSlash = path.lastIndexOf('/', lastSlash - 1);

		if(LOG) Log.w(TAG, "getReadableFolderName prevSlash=" + prevSlash);

		if(prevSlash == -1) {
			return path.substring(0, lastSlash);
		}
		if(prevSlash == 0) {
			return path.substring(1, lastSlash);
		}

		int prevPrevSlash = path.lastIndexOf('/', prevSlash - 1);

		if(LOG) Log.w(TAG, "getReadableFolderName prevPrevSlash=" + prevPrevSlash);

		if(prevPrevSlash == -1) {
			return path.substring(0, lastSlash);
		}
		if(prevPrevSlash == 0) {
			return path.substring(1, lastSlash);
		}

		return path.substring(prevPrevSlash + 1, lastSlash);
	}

	public final int getFlags() {
		return flags;
	}
	
	public final int getAdvance() {
		return flags & FLAG_ADVANCE_MASK;
	}
	
	public final boolean isAdvancedByCat() {
		int advance = getAdvance();
		return advance == FLAG_ADVANCE_BACKWARD_CAT || advance == FLAG_ADVANCE_FORWARD_CAT;
	}
	
	@SuppressWarnings("null")
	public @NonNull Builder buildFileUri() {
		// NOTE: for rawFiles, we have fileUri == all files - for UI purposes as there is no raw_files category, 
		// but we still want that given file uri to be within raw_files for proper actions handling
		Uri uri;
		if(!isRaw()) {
			uri = filesUri; 
		} else {
			uri = actualLoadedFilesListUri;
			
		}
		Builder b = uri.buildUpon()
				.appendEncodedPath(Long.toString(entryId != PowerampAPI.NO_ID ? entryId : fileId))
				.encodedFragment(null)
				.encodedQuery(null);
		return b;
	}
	
	public String getCounterText() {
		if(isRaw() || listSize == 0) {
			return "- / -";
		}
		
		if(mCounterText == null) { // NOTE: sync / membar on string is not needed here (same as for android.net.Uri caching)
			mCounterText = (position + 1) + " / " + listSize;
		}
		return mCounterText;
	}

	@Override
	public String toString() {
		return "Track " + this.hashCode() + " npSerial=" + npSerial + " mFilesUri=" + filesUri + " catUriMatch=" + catUriMatch + " fileId=" + fileId + " entryId=" + entryId + " folderId=" + folderId + " path=" + path + " title=" + title + " album=" + album + " artist=" 
				+ artist + " durationMS=" + getDurationMS() + " tagStatus=" + tagStatus + " fileType=" + fileType + " position=" + position
				+ " isCue=" + isCue + " " + " cueOffsetMs=" + cueOffsetMs + " flags=0x" + Integer.toHexString(flags) + "  trackSampleRate=" + trackSampleRate 
				+ " trackChannels=" + trackChannels + " trackDurationMS=" + trackDurationMS + " bitRate=" + trackBitRate + " bitsPerSimple=" + trackBitsPerSample 
				+ " codec=" + trackCodec + " isGapless=" + trackIsGapless + " rgType=" + trackRgType
				; 
	}

	@Override
	public boolean equals(Object o) {
		if(o == this) {
			return true;
		}

		if(o instanceof Track) {
			Track track = (Track)o;
			return position == track.position // NOTE: most of the tracks will fail here, on first condition
					&& npSerial == track.npSerial
					&& fileId == track.fileId //
					&& folderId == track.folderId //
					&& albumId == track.albumId //
					&& artistId == track.artistId //
					&& entryId == track.entryId //
					&& catUriMatch == track.catUriMatch //
					&& listSize == track.listSize
					&& isCue == track.isCue
					&& cueOffsetMs == track.cueOffsetMs
					&& trackNum == track.trackNum
					&& fileType == track.fileType
					&& tagStatus == track.tagStatus
					//&& equPresetId == track.equPresetId
					//&& equPresetIndex == track.equPresetIndex
					&& rating == track.rating
					&& TextUtils.equals(path, track.path) 
					&& shuffle == track.shuffle
					&& TextUtils.equals(album, track.album) // NOTE: need to compare strings as we can have edited tags
					&& TextUtils.equals(artist, track.artist)
					&& TextUtils.equals(albumArtist, track.albumArtist)
					&& TextUtils.equals(title, track.title)
					;
			// NOTE: not comparing track.serial (as it differs for each created track instance)

			// Everything is the same for the track, but files uri can be different, as it doesn't belong to the track itself, but to parent cursor
			// and depends on query, parameters, etc.
			// But, as npSerial is the same, filesUri is guaranteed to be the same. Just because we change npSerial on any npd cursors change.
			// NOTE: we may have updated durationMS in the current track, so don't check it.
		}
		return false;
	}

	@Override
	public int hashCode() {
		return super.hashCode(); // Just to remove warning. Not storing Track as hash key now
	}
}

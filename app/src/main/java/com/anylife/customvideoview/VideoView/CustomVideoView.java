package com.anylife.customvideoview.VideoView;

import android.content.Context;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.MediaController;
import android.widget.MediaController.MediaPlayerControl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 可以通用于播放HTTP、HLS、RTSP、Local File。如果部分设备不支持，可以使用软解（软解库可以使用FFPEG等，本Demo没有）。
 * 这只是基本裁剪，没有多余的功能，优化
 *
 * @author liubao.zeng
 * @version 2013-1-2 创建 ，修订2016-11-11，基于Android 6.0 的videoview 源码修订
 *          <p>
 *          <p>
 *          Displays a video file.  The VideoView class
 *          can load images from various sources (such as resources or content
 *          providers), takes care of computing its measurement from the video so that
 *          it can be used in any layout manager, and provides various display options
 *          such as scaling and tinting.<p>
 *          <p>
 *          <em>Note: VideoView does not retain its full state when going into the
 *          background.</em>  In particular, it does not restore the current play state,
 *          play position, selected tracks, or any subtitle tracks added via
 *          Applications should
 *          save and restore these on their own in
 *          {@link android.app.Activity#onSaveInstanceState} and
 *          {@link android.app.Activity#onRestoreInstanceState}.<p>
 *          Also note that the audio session id (from {@link #getAudioSessionId}) may
 *          change from its previously returned value when the VideoView is restored.
 */
public class CustomVideoView extends SurfaceView implements MediaPlayerControl {
	private String TAG = "CustomVideoView";
	// settable by the client
	private Map<String, String> mHeaders;

	// all possible internal states
	private static final int STATE_ERROR = -1;
	private static final int STATE_IDLE = 0;
	private static final int STATE_PREPARING = 1;
	private static final int STATE_PREPARED = 2;
	private static final int STATE_PLAYING = 3;
	private static final int STATE_PAUSED = 4;
	private static final int STATE_PLAYBACK_COMPLETED = 5;

	// mCurrentState is a VideoView object's current state.
	// mTargetState is the state that a method caller intends to reach.
	// For instance, regardless the VideoView object's current state,
	// calling pause() intends to bring the object to a target state
	// of STATE_PAUSED.
	private int mCurrentState = STATE_IDLE;
	private int mTargetState = STATE_IDLE;

	// All the stuff we need for playing and showing a video
	private SurfaceHolder mSurfaceHolder = null;
	private MediaPlayer mMediaPlayer = null;
	private int mAudioSession;
	private int mVideoWidth;
	private int mVideoHeight;
	private int mSurfaceWidth;
	private int mSurfaceHeight;
	private MediaController mMediaController;
	private OnCompletionListener mOnCompletionListener;
	private MediaPlayer.OnPreparedListener mOnPreparedListener;
	private int mCurrentBufferPercentage;
	private OnErrorListener mOnErrorListener;
	private OnInfoListener mOnInfoListener;
	private int mSeekWhenPrepared;  // recording the seek position while preparing
	private boolean mCanPause;
	private boolean mCanSeekBack;
	private boolean mCanSeekForward;
	private Context mContext;

	public CustomVideoView(Context context) {
		super(context);
		mContext = context;

		initVideoView();
	}

	public CustomVideoView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
		mContext = context;

		initVideoView();
	}

	public CustomVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		mContext = context;

		initVideoView();
	}

//    public VideoView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
//        super(context, attrs, defStyleAttr, defStyleRes);
//        initVideoView();
//    }

	/**
	 * 测量视图的大小,这应该是最复杂的视图绘制流程了！你也可以简单一点的改写
	 *
	 * @param widthMeasureSpec    由父视图经过计算后传递给子视图的
	 * @param heightMeasureSpec   由父视图经过计算后传递给子视图的
	 */
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		Log.i("View", "onMeasure(" + MeasureSpec.toString(widthMeasureSpec) + ", " + MeasureSpec.toString(heightMeasureSpec) + ")");

		int width = getDefaultSize(mVideoWidth, widthMeasureSpec);
		int height = getDefaultSize(mVideoHeight, heightMeasureSpec);
		if (mVideoWidth > 0 && mVideoHeight > 0) {

			int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
			int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
			int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
			int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

			if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
				// the size is fixed
				width = widthSpecSize;
				height = heightSpecSize;

				// for compatibility, we adjust size based on aspect ratio
				if (mVideoWidth * height < width * mVideoHeight) {
					Log.i("View", "image too wide, correcting");
					width = height * mVideoWidth / mVideoHeight;
				} else if (mVideoWidth * height > width * mVideoHeight) {
					Log.i("View", "image too tall, correcting");
					height = width * mVideoHeight / mVideoWidth;
				}
			} else if (widthSpecMode == MeasureSpec.EXACTLY) {
				// only the width is fixed, adjust the height to match aspect ratio if possible
				width = widthSpecSize;
				height = width * mVideoHeight / mVideoWidth;
				if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
					// couldn't match aspect ratio within the constraints
					height = heightSpecSize;
				}
			} else if (heightSpecMode == MeasureSpec.EXACTLY) {
				// only the height is fixed, adjust the width to match aspect ratio if possible
				height = heightSpecSize;
				width = height * mVideoWidth / mVideoHeight;
				if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
					// couldn't match aspect ratio within the constraints
					width = widthSpecSize;
				}
			} else {
				// neither the width nor the height are fixed, try to use actual video size
				width = mVideoWidth;
				height = mVideoHeight;
				if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
					// too tall, decrease both width and height
					height = heightSpecSize;
					width = height * mVideoWidth / mVideoHeight;
				}
				if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
					// too wide, decrease both width and height
					width = widthSpecSize;
					height = width * mVideoHeight / mVideoWidth;
				}
			}
		} else {
			// no size yet, just adopt the given spec sizes



		}
		setMeasuredDimension(width, height);
	}

	@Override
	public CharSequence getAccessibilityClassName() {
		return CustomVideoView.class.getName();
	}

	public int resolveAdjustedSize(int desiredSize, int measureSpec) {
		return getDefaultSize(desiredSize, measureSpec);
	}

	private void initVideoView() {
		mVideoWidth = 0;
		mVideoHeight = 0;
		getHolder().addCallback(mSHCallback);
		getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		setFocusable(true);
		setFocusableInTouchMode(true);
		requestFocus();
		mCurrentState = STATE_IDLE;
		mTargetState = STATE_IDLE;
	}

	private Uri mUri;

	private List<String> videoPaths = new ArrayList<>();
	private int currentVideoIndex = 0;

	/**
	 * 设置播放路径集合
	 *
	 * @param videoPathsArg
	 */
	public void setVideoPaths(@NonNull List<String> videoPathsArg) {
		currentVideoIndex = 0;
		videoPaths.clear();
		videoPaths.addAll(videoPathsArg);
		setVideoURI(Uri.parse(videoPaths.get(currentVideoIndex)), null);
	}

	/**
	 * 设置单个播放路径
	 *
	 * @param videoPathsArg
	 */
	public void setVideoPath(@NonNull String videoPathsArg) {
		currentVideoIndex = 0;
		videoPaths.clear();
		videoPaths.add(videoPathsArg);
		setVideoURI(Uri.parse(videoPaths.get(currentVideoIndex)), null);
	}


	/**
	 * 添加一个播放路径
	 *
	 * @param videoPathsArg
	 */
	public void addVideoPath(@NonNull String videoPathsArg) {
		videoPaths.add(videoPathsArg);
	}

	/**
	 * 播放下一个视频
	 */
	public void playNextVideo() {
		currentVideoIndex = (currentVideoIndex + 1) % videoPaths.size();
		setVideoURI(Uri.parse(videoPaths.get(currentVideoIndex)), null);
	}

	/**
	 * 错误处理
	 */
	private void mediaErrorDispose(int framework_err, int impl_err) {
		switch (framework_err) {
			case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
				// 视频不可以回退
				break;
			case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
				Log.d(TAG, "流媒体服务器端异常!");
				playNextVideo();
				break;
			case MediaPlayer.MEDIA_ERROR_UNKNOWN:
				Log.d(TAG, "不能播放的视频文件！");
//                playNextVideo();  //直播的时候好像会经常出现这个问题！
				break;
			default:                //不知道的错误类型不知道怎样处理
				playNextVideo();
				break;
		}
	}

	/**
	 * Sets video URI using specific headers.
	 *
	 * @param uri     the URI of the video.
	 * @param headers the headers for the URI request.
	 *                Note that the cross domain redirection is allowed by default, but that can be
	 *                changed with key/value pairs through the headers parameter with
	 *                "android-allow-cross-domain-redirect" as the key and "0" or "1" as the value
	 *                to disallow or allow cross domain redirection.
	 */
	public void setVideoURI(Uri uri, Map<String, String> headers) {
		mUri = uri;
		mHeaders = headers;
		mSeekWhenPrepared = 0;
		openVideo();
		requestLayout();
		invalidate();
	}

//	private Vector<Pair<InputStream, MediaFormat>> mPendingSubtitleTracks;

	public void stopPlayback() {
		if (mMediaPlayer != null) {
			mMediaPlayer.stop();
			mMediaPlayer.release();
			mMediaPlayer = null;
			mCurrentState = STATE_IDLE;
			mTargetState = STATE_IDLE;
			AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
			am.abandonAudioFocus(null);
		}
	}

	private void openVideo() {
		if (mUri == null || mSurfaceHolder == null) {
			// not ready for playback just yet, will try again later
			return;
		}
		// we shouldn't clear the target state, because somebody might have
		// called start() previously
		release(false);

		AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
		am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

		try {
			mMediaPlayer = new MediaPlayer();
			if (mAudioSession != 0) {
				mMediaPlayer.setAudioSessionId(mAudioSession);
			} else {
				mAudioSession = mMediaPlayer.getAudioSessionId();
			}
			mMediaPlayer.setOnPreparedListener(mPreparedListener);
			mMediaPlayer.setOnVideoSizeChangedListener(mSizeChangedListener);
			mMediaPlayer.setOnCompletionListener(mCompletionListener);
			mMediaPlayer.setOnErrorListener(mErrorListener);
			mMediaPlayer.setOnInfoListener(mInfoListener);
			mMediaPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
			mCurrentBufferPercentage = 0;
			mMediaPlayer.setDataSource(mContext, mUri, mHeaders);
			mMediaPlayer.setDisplay(mSurfaceHolder);
			mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			mMediaPlayer.setScreenOnWhilePlaying(true);
			mMediaPlayer.prepareAsync();

			// we don't set the target state here either, but preserve the
			// target state that was there before.
			mCurrentState = STATE_PREPARING;
//			attachMediaController();
		} catch (IOException ex) {
			Log.w(TAG, "Unable to open content: " + mUri, ex);
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
			return;
		} catch (IllegalArgumentException ex) {
			Log.w(TAG, "Unable to open content: " + mUri, ex);
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
			return;
		} finally {
//			mPendingSubtitleTracks.clear();
		}
	}


	MediaPlayer.OnVideoSizeChangedListener mSizeChangedListener =
			new MediaPlayer.OnVideoSizeChangedListener() {
				public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
					mVideoWidth = mp.getVideoWidth();
					mVideoHeight = mp.getVideoHeight();
					if (mVideoWidth != 0 && mVideoHeight != 0) {
						getHolder().setFixedSize(mVideoWidth, mVideoHeight);
						requestLayout();
					}
				}
			};

	MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
		public void onPrepared(MediaPlayer mp) {
			mCurrentState = STATE_PREPARED;

			// Get the capabilities of the player for this stream
//			Metadata data = mp.getMetadata(MediaPlayer.METADATA_ALL, MediaPlayer.BYPASS_METADATA_FILTER);
//
//			if (data != null) {
//				mCanPause = !data.has(Metadata.PAUSE_AVAILABLE)
//						|| data.getBoolean(Metadata.PAUSE_AVAILABLE);
//				mCanSeekBack = !data.has(Metadata.SEEK_BACKWARD_AVAILABLE)
//						|| data.getBoolean(Metadata.SEEK_BACKWARD_AVAILABLE);
//				mCanSeekForward = !data.has(Metadata.SEEK_FORWARD_AVAILABLE)
//						|| data.getBoolean(Metadata.SEEK_FORWARD_AVAILABLE);
//			}else
			{
				mCanPause = mCanSeekBack = mCanSeekForward = true;
			}

			if (mOnPreparedListener != null) {
				mOnPreparedListener.onPrepared(mMediaPlayer);
			}
			if (mMediaController != null) {
				mMediaController.setEnabled(true);
			}
			mVideoWidth = mp.getVideoWidth();
			mVideoHeight = mp.getVideoHeight();

			int seekToPosition = mSeekWhenPrepared;  // mSeekWhenPrepared may be changed after seekTo() call
			if (seekToPosition != 0) {
				seekTo(seekToPosition);
			}
			if (mVideoWidth != 0 && mVideoHeight != 0) {
				//Log.i("View@", "video size: " + mVideoWidth +"/"+ mVideoHeight);
				getHolder().setFixedSize(mVideoWidth, mVideoHeight);
				if (mSurfaceWidth == mVideoWidth && mSurfaceHeight == mVideoHeight) {

					// We didn't actually change the size (it was already at the size
					// we need), so we won't get a "surface changed" callback, so
					// start the video here instead of in the callback.
					if (mTargetState == STATE_PLAYING) {
						start();
						if (mMediaController != null) {
							mMediaController.show();
						}
					} else if (!isPlaying() && (seekToPosition != 0 || getCurrentPosition() > 0)) {
						if (mMediaController != null) {
							// Show the media controls when we're paused into a video and make 'em stick.
							mMediaController.show(0);
						}
					}
				}
			} else {
				// We don't know the video size yet, but should start anyway.
				// The video size might be reported to us later.
				if (mTargetState == STATE_PLAYING) {
					start();
				}
			}
		}
	};

	private OnCompletionListener mCompletionListener =
			new OnCompletionListener() {
				public void onCompletion(MediaPlayer mp) {
					mCurrentState = STATE_PLAYBACK_COMPLETED;
					mTargetState = STATE_PLAYBACK_COMPLETED;
					if (mMediaController != null) {
						mMediaController.hide();
					}
					if (mOnCompletionListener != null) {
						mOnCompletionListener.onCompletion(mMediaPlayer);
					}
				}
			};

	private OnInfoListener mInfoListener =
			new OnInfoListener() {
				public boolean onInfo(MediaPlayer mp, int arg1, int arg2) {
					Log.e(TAG, arg1 + "    info   " + arg2);
					if (mOnInfoListener != null) {
						mOnInfoListener.onInfo(mp, arg1, arg2);
					}
					if (arg1 == MediaPlayer.MEDIA_INFO_BUFFERING_START) {//你不要弄反了
//						mMediaPlayer.pause();  //不用了，jni 会自动的pause
					} else if (arg1 == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
//						mMediaPlayer.start();
					}

					return true;
				}
			};

	private OnErrorListener mErrorListener =
			new OnErrorListener() {
				public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
					Log.d(TAG, "Error: " + framework_err + "," + impl_err);

					mediaErrorDispose(framework_err, impl_err);

					mCurrentState = STATE_ERROR;
					mTargetState = STATE_ERROR;
					if (mMediaController != null) {
						mMediaController.hide();
					}

            /* If an error handler has been supplied, use it and finish. */
					if (mOnErrorListener != null) {
						if (mOnErrorListener.onError(mMediaPlayer, framework_err, impl_err)) {
							return true;
						}
					}

            /* Otherwise, pop up an error dialog so the user knows that
			 * something bad has happened. Only try and pop up the dialog
             * if we're attached to a window. When we're going away and no
             * longer have a window, don't bother showing the user an error.
             */
//					if (getWindowToken() != null) {
//						Resources r = mContext.getResources();
//						int messageId;
//
//						if (framework_err == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
//							messageId = R.string.VideoView_error_text_invalid_progressive_playback;
//						} else {
//							messageId = R.string.VideoView_error_text_unknown;
//						}
//
//						new AlertDialog.Builder(mContext)
//								.setMessage(messageId)
//								.setPositiveButton("OK",
//										new DialogInterface.OnClickListener() {
//											public void onClick(DialogInterface dialog, int whichButton) {
//										/* If we get here, there is no onError listener, so
//										 * at least inform them that the video is over.
//                                         */
//												if (mOnCompletionListener != null) {
//													mOnCompletionListener.onCompletion(mMediaPlayer);
//												}
//											}
//										})
//								.setCancelable(false)
//								.show();
//
//					}
					return true;
				}
			};

	private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener =
			new MediaPlayer.OnBufferingUpdateListener() {
				public void onBufferingUpdate(MediaPlayer mp, int percent) {
					mCurrentBufferPercentage = percent;
					Log.e(TAG, "percent" + percent);
				}
			};

	/**
	 * Register a callback to be invoked when the media file
	 * is loaded and ready to go.
	 *
	 * @param l The callback that will be run
	 */
	public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
		mOnPreparedListener = l;
	}

	/**
	 * Register a callback to be invoked when the end of a media file
	 * has been reached during playback.
	 *
	 * @param l The callback that will be run
	 */
	public void setOnCompletionListener(OnCompletionListener l) {
		mOnCompletionListener = l;
	}

	/**
	 * Register a callback to be invoked when an error occurs
	 * during playback or setup.  If no listener is specified,
	 * or if the listener returned false, VideoView will inform
	 * the user of any errors.
	 *
	 * @param l The callback that will be run
	 */
	public void setOnErrorListener(OnErrorListener l) {
		mOnErrorListener = l;
	}

	/**
	 * Register a callback to be invoked when an informational event
	 * occurs during playback or setup.
	 *
	 * @param l The callback that will be run
	 */
	public void setOnInfoListener(OnInfoListener l) {
		mOnInfoListener = l;
	}

	SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback() {
		public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
			mSurfaceWidth = w;
			mSurfaceHeight = h;
			boolean isValidState = (mTargetState == STATE_PLAYING);
			boolean hasValidSize = (mVideoWidth == w && mVideoHeight == h);
			if (mMediaPlayer != null && isValidState && hasValidSize) {
				if (mSeekWhenPrepared != 0) {
					seekTo(mSeekWhenPrepared);
				}
				start();
			}
		}

		public void surfaceCreated(SurfaceHolder holder) {
			mSurfaceHolder = holder;
			openVideo();
		}

		public void surfaceDestroyed(SurfaceHolder holder) {
			// after we return from this we can't use the surface any more
			mSurfaceHolder = null;
			if (mMediaController != null) mMediaController.hide();
			release(true);
		}
	};

	/*
	 * release the media player in any state
	 */
	private void release(boolean cleartargetstate) {
		if (mMediaPlayer != null) {
			mMediaPlayer.reset();
			mMediaPlayer.release();
			mMediaPlayer = null;
			mCurrentState = STATE_IDLE;
			if (cleartargetstate) {
				mTargetState = STATE_IDLE;
			}
			AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
			am.abandonAudioFocus(null);
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		if (isInPlaybackState() && mMediaController != null) {
			toggleMediaControlsVisiblity();
		}
		return false;
	}

	@Override
	public boolean onTrackballEvent(MotionEvent ev) {
		if (isInPlaybackState() && mMediaController != null) {
			toggleMediaControlsVisiblity();
		}
		return false;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK &&
				keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
				keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
				keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
				keyCode != KeyEvent.KEYCODE_MENU &&
				keyCode != KeyEvent.KEYCODE_CALL &&
				keyCode != KeyEvent.KEYCODE_ENDCALL;
		if (isInPlaybackState() && isKeyCodeSupported && mMediaController != null) {
			if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
					keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
				if (mMediaPlayer.isPlaying()) {
					pause();
					mMediaController.show();
				} else {
					start();
					mMediaController.hide();
				}
				return true;
			} else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
				if (!mMediaPlayer.isPlaying()) {
					start();
					mMediaController.hide();
				}
				return true;
			} else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
					|| keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
				if (mMediaPlayer.isPlaying()) {
					pause();
					mMediaController.show();
				}
				return true;
			} else {
				toggleMediaControlsVisiblity();
			}
		}

		return super.onKeyDown(keyCode, event);
	}

	private void toggleMediaControlsVisiblity() {
		if (mMediaController.isShowing()) {
			mMediaController.hide();
		} else {
			mMediaController.show();
		}
	}

	@Override
	public void start() {
		if (isInPlaybackState()) {
			mMediaPlayer.start();
			mCurrentState = STATE_PLAYING;
		}
		mTargetState = STATE_PLAYING;
	}

	@Override
	public void pause() {
		if (isInPlaybackState()) {
			if (mMediaPlayer.isPlaying()) {
				mMediaPlayer.pause();
				mCurrentState = STATE_PAUSED;
			}
		}
		mTargetState = STATE_PAUSED;
	}

	public void suspend() {
		release(false);
	}

	public void resume() {
		openVideo();
	}

	@Override
	public int getDuration() {
		if (isInPlaybackState()) {
			return mMediaPlayer.getDuration();
		}

		return -1;
	}

	@Override
	public int getCurrentPosition() {
		if (isInPlaybackState()) {
			return mMediaPlayer.getCurrentPosition();
		}
		return 0;
	}

	@Override
	public void seekTo(int msec) {
		if (isInPlaybackState()) {
			mMediaPlayer.seekTo(msec);
			mSeekWhenPrepared = 0;
		} else {
			mSeekWhenPrepared = msec;
		}
	}

	@Override
	public boolean isPlaying() {
		return isInPlaybackState() && mMediaPlayer.isPlaying();
	}

	@Override
	public int getBufferPercentage() {
		if (mMediaPlayer != null) {
			return mCurrentBufferPercentage;
		}
		return 0;
	}

	private boolean isInPlaybackState() {
		return (mMediaPlayer != null &&
				mCurrentState != STATE_ERROR &&
				mCurrentState != STATE_IDLE &&
				mCurrentState != STATE_PREPARING);
	}

	@Override
	public boolean canPause() {
		return mCanPause;
	}

	@Override
	public boolean canSeekBackward() {
		return mCanSeekBack;
	}

	@Override
	public boolean canSeekForward() {
		return mCanSeekForward;
	}

	@Override
	public int getAudioSessionId() {
		if (mAudioSession == 0) {
			MediaPlayer foo = new MediaPlayer();
			mAudioSession = foo.getAudioSessionId();
			foo.release();
		}
		return mAudioSession;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();

	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();

	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);
		Log.i("View","onLayout：   changed"+changed+"   left:"+left+"    Top:"+top+"   right:"+right+"  bottom:"+bottom);
	}

	@Override
	public void draw(Canvas canvas) {
		super.draw(canvas);
		Log.i("View","onLayout：   canvas"+canvas.toString());
	}


}

/*
 * Copyright 2009 Andrew Shu
 *
 * This file is part of "reddit is fun".
 *
 * "reddit is fun" is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * "reddit is fun" is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with "reddit is fun".  If not, see <http://www.gnu.org/licenses/>.
 */

package com.andrewshu.android.reddit;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Main Activity class representing a Subreddit, i.e., a ThreadsList.
 * 
 * @author TalkLittle
 *
 */
public final class RedditIsFun extends ListActivity {

	private static final String TAG = "RedditIsFun";
	
	private final JsonFactory jsonFactory = new JsonFactory(); 
	
    /** Custom list adapter that fits our threads data into the list. */
    private ThreadsListAdapter mThreadsAdapter = null;
    private ArrayList<ThreadInfo> mThreadsList = null;

    private final DefaultHttpClient mClient = Common.getGzipHttpClient();
	
   
    private final RedditSettings mSettings = new RedditSettings();
    
    // UI State
    private ThreadInfo mVoteTargetThreadInfo = null;
    private AsyncTask mCurrentDownloadThreadsTask = null;
    private final Object mCurrentDownloadThreadsTaskLock = new Object();

    // Whether it should use the cache. Otherwise download from Internet.
    volatile private boolean mShouldUseThreadsCache = true;
    
    // Navigation that can be cached
    private CharSequence mAfter = null;
    private CharSequence mBefore = null;
    private CharSequence mUrlToGetHere = null;
    private boolean mUrlToGetHereChanged = true;
    private CharSequence mSortByUrl = Constants.ThreadsSort.SORT_BY_HOT_URL;
    private CharSequence mSortByUrlExtra = Constants.EMPTY_STRING;
    private volatile int mCount = Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT;
    private CharSequence mJumpToThreadId = null;
    private long mLastRefreshTime = 0;
    // End navigation variables
    
    // ProgressDialogs with percentage bars
    private AutoResetProgressDialog mLoadingThreadsProgress;
    
    // Menu
    private boolean mCanChord = false;
    
    
    /**
     * Called when the activity starts up. Do activity initialization
     * here, not in a constructor.
     * 
     * @see Activity#onCreate
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Common.loadRedditPreferences(getApplicationContext(), mSettings, mClient);
        setRequestedOrientation(mSettings.rotation);
        setTheme(mSettings.theme);
        
        setContentView(R.layout.threads_list_content);
        // The above layout contains a list id "android:list"
        // which ListActivity adopts as its list -- we can
        // access it with getListView().
        ListView lv = getListView();
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
        	@Override
        	public boolean onItemLongClick(AdapterView<?> av, View v, int pos, long id) {
        	   onLongListItemClick(v,pos,id);
        	   return true; 
        	}});

        if (savedInstanceState != null) {
	        CharSequence subreddit = savedInstanceState.getCharSequence(ThreadInfo.SUBREDDIT);
	        if (subreddit != null)
	        	mSettings.setSubreddit(subreddit);
	        else
	        	mSettings.setSubreddit(mSettings.homepage);
	        mUrlToGetHere = savedInstanceState.getCharSequence(Constants.URL_TO_GET_HERE_KEY);
		    mCount = savedInstanceState.getInt(Constants.THREAD_COUNT);
		    mSortByUrl = savedInstanceState.getCharSequence(Constants.ThreadsSort.SORT_BY_KEY);
		    mJumpToThreadId = savedInstanceState.getCharSequence(Constants.JUMP_TO_THREAD_ID_KEY);
        } else {
        	mSettings.setSubreddit(mSettings.homepage);
        }
        new ReadCacheTask().execute();
    }
    
    @Override
    protected void onResume() {
    	super.onResume();
    	int previousTheme = mSettings.theme;
    	boolean previousLoggedIn = mSettings.loggedIn;
    	Common.loadRedditPreferences(getApplicationContext(), mSettings, mClient);
    	setRequestedOrientation(mSettings.rotation);
    	if (mSettings.theme != previousTheme) {
    		setTheme(mSettings.theme);
    		setContentView(R.layout.threads_list_content);
    		setListAdapter(mThreadsAdapter);
    		Common.updateListDrawables(this, mSettings.theme);
    	}
    	if (mSettings.loggedIn != previousLoggedIn)
    		mShouldUseThreadsCache = false;
    	if (mThreadsAdapter == null)
    		new ReadCacheTask().execute();
    	else
    		jumpToThread();
    	new Common.PeekEnvelopeTask(this, mClient, mSettings.mailNotificationStyle).execute();
    }
    
    /**
     * Hack to explicitly set background color whenever changing ListView.
     */
    public void setContentView(int layoutResID) {
    	super.setContentView(layoutResID);
    	// HACK: set background color directly for android 2.0
        if (mSettings.theme == R.style.Reddit_Light)
        	getListView().setBackgroundResource(R.color.white);
    }
    
    @Override
    protected void onPause() {
    	super.onPause();
    	Common.saveRedditPreferences(getApplicationContext(), mSettings);
    }
    

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
    	super.onActivityResult(requestCode, resultCode, intent);
    	
    	switch(requestCode) {
    	case Constants.ACTIVITY_PICK_SUBREDDIT:
    		if (resultCode == Activity.RESULT_OK) {
    			Bundle extras = intent.getExtras();
	    		String newSubreddit = extras.getString(ThreadInfo.SUBREDDIT);
	    		if (newSubreddit != null && !"".equals(newSubreddit)) {
	    			mSettings.setSubreddit(newSubreddit);
	    			resetUrlToGetHere();
	    			new DownloadThreadsTask().execute(newSubreddit);
	    		}
    		}
    		break;
    	case Constants.ACTIVITY_SUBMIT_LINK:
    		if (resultCode == Activity.RESULT_OK) {
    			Bundle extras = intent.getExtras();
	    		String newSubreddit = extras.getString(ThreadInfo.SUBREDDIT);
	    		String newId = extras.getString(ThreadInfo.ID);
	    		String newTitle = extras.getString(ThreadInfo.TITLE);
	    		mSettings.setSubreddit(newSubreddit);
	    		// Start up comments list with the new thread
	    		Intent i = new Intent(getApplicationContext(), CommentsListActivity.class);
				i.putExtra(ThreadInfo.SUBREDDIT, newSubreddit);
				i.putExtra(ThreadInfo.ID, newId);
				i.putExtra(ThreadInfo.TITLE, newTitle);
				i.putExtra(ThreadInfo.NUM_COMMENTS, 0);
				startActivity(i);
    		} else if (resultCode == Constants.RESULT_LOGIN_REQUIRED) {
    			Common.showErrorToast("You must be logged in to make a submission.", Toast.LENGTH_LONG, this);
    		}
    		break;
    	default:
    		break;
    	}
    }
    
    
    private final class ThreadsListAdapter extends ArrayAdapter<ThreadInfo> {
    	static final int THREAD_ITEM_VIEW_TYPE = 0;
    	static final int MORE_ITEM_VIEW_TYPE = 1;
    	// The number of view types
    	static final int VIEW_TYPE_COUNT = 2;
    	public boolean mIsLoading = true;
    	private LayoutInflater mInflater;
//        private boolean mDisplayThumbnails = false; // TODO: use this
//        private SparseArray<SoftReference<Bitmap>> mBitmapCache = null; // TODO?: use this?
    	private int mFrequentSeparatorPos = ListView.INVALID_POSITION;
        
        public ThreadsListAdapter(Context context, List<ThreadInfo> objects) {
            super(context, 0, objects);
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }
        
        @Override
        public int getItemViewType(int position) {
        	if (position == mFrequentSeparatorPos) {
                // We don't want the separator view to be recycled.
                return IGNORE_ITEM_VIEW_TYPE;
            }
            if (position < getCount() - 1 || getCount() < Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT + 1)
        		return THREAD_ITEM_VIEW_TYPE;
        	return MORE_ITEM_VIEW_TYPE;
        }
        
        @Override
        public int getViewTypeCount() {
        	return VIEW_TYPE_COUNT;
        }

        @Override
        public boolean isEmpty() {
        	if (mIsLoading)
        		return false;
        	return super.isEmpty();
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            Resources res = getResources();

            if (position < getCount() - 1 || getCount() < Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT + 1) {
	            // Here view may be passed in for re-use, or we make a new one.
	            if (convertView == null) {
	                view = mInflater.inflate(R.layout.threads_list_item, null);
	            } else {
	                view = convertView;
	            }
	            
	            ThreadInfo item = this.getItem(position);
	            
	            // Set the values of the Views for the ThreadsListItem
	            
	            TextView titleView = (TextView) view.findViewById(R.id.title);
	            TextView votesView = (TextView) view.findViewById(R.id.votes);
	            TextView numCommentsView = (TextView) view.findViewById(R.id.numComments);
	            TextView subredditView = (TextView) view.findViewById(R.id.subreddit);
	//            TextView submissionTimeView = (TextView) view.findViewById(R.id.submissionTime);
	            ImageView voteUpView = (ImageView) view.findViewById(R.id.vote_up_image);
	            ImageView voteDownView = (ImageView) view.findViewById(R.id.vote_down_image);
	            
	            // Set the title and domain using a SpannableStringBuilder
	            SpannableStringBuilder builder = new SpannableStringBuilder();
	            String title = item.getTitle().replaceAll("\n ", " ").replaceAll(" \n", " ").replaceAll("\n", " ");
	            SpannableString titleSS = new SpannableString(title);
	            int titleLen = title.length();
	            TextAppearanceSpan titleTAS = new TextAppearanceSpan(getApplicationContext(), R.style.TextAppearance_14sp);
	            titleSS.setSpan(titleTAS, 0, titleLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	            if (mSettings.theme == R.style.Reddit_Light) {
	            	// FIXME: This doesn't work persistently, since "clicked" is not delivered to reddit.com
		            if (Constants.TRUE_STRING.equals(item.getClicked())) {
		            	ForegroundColorSpan fcs = new ForegroundColorSpan(res.getColor(R.color.purple));
		            	titleSS.setSpan(fcs, 0, titleLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		            } else {
		            	ForegroundColorSpan fcs = new ForegroundColorSpan(res.getColor(R.color.blue));
		            	titleSS.setSpan(fcs, 0, titleLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		            }
	            }
	            builder.append(titleSS);
	            builder.append(" ");
	            SpannableString domainSS = new SpannableString("("+item.getDomain()+")");
	            TextAppearanceSpan domainTAS = new TextAppearanceSpan(getApplicationContext(), R.style.TextAppearance_10sp);
	            domainSS.setSpan(domainTAS, 0, item.getDomain().length()+2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	            builder.append(domainSS);
	            titleView.setText(builder);
	            
	            votesView.setText(item.getScore());
	            numCommentsView.setText(item.getNumComments()+" comments");
	            if (mSettings.isFrontpage) {
	            	subredditView.setVisibility(View.VISIBLE);
	            	subredditView.setText(item.getSubreddit());
	            } else {
	            	subredditView.setVisibility(View.GONE);
	            }
	//            submitterView.setText(item.getAuthor());
	            // TODO: convert submission time to a displayable time
	//            Date submissionTimeDate = new Date((long) (Double.parseDouble(item.getCreated()) / 1000));
	//            submissionTimeView.setText("5 hours ago");
	            
	            // Set the up and down arrow colors based on whether user likes
	            if (mSettings.loggedIn) {
	            	if (Constants.TRUE_STRING.equals(item.getLikes())) {
	            		voteUpView.setImageResource(R.drawable.vote_up_red);
	            		voteDownView.setImageResource(R.drawable.vote_down_gray);
	            		votesView.setTextColor(res.getColor(R.color.arrow_red));
	            	} else if (Constants.FALSE_STRING.equals(item.getLikes())) {
	            		voteUpView.setImageResource(R.drawable.vote_up_gray);
	            		voteDownView.setImageResource(R.drawable.vote_down_blue);
	            		votesView.setTextColor(res.getColor(R.color.arrow_blue));
	            	} else {
	            		voteUpView.setImageResource(R.drawable.vote_up_gray);
	            		voteDownView.setImageResource(R.drawable.vote_down_gray);
	            		votesView.setTextColor(res.getColor(R.color.gray));
	            	}
	            } else {
	        		voteUpView.setImageResource(R.drawable.vote_up_gray);
	        		voteDownView.setImageResource(R.drawable.vote_down_gray);
	        		votesView.setTextColor(res.getColor(R.color.gray));
	            }
	            
	            // TODO?: Thumbnail
	//            view.getThumbnail().
	
	            // TODO: If thumbnail, download it and create ImageView
	            // Some thumbnails may be absolute paths instead of URLs:
	            // "/static/noimage.png"
	            
	            // Set the proper icon (star or presence or nothing)
	//            ImageView presenceView = cache.presenceView;
	//            if ((mMode & MODE_MASK_NO_PRESENCE) == 0) {
	//                int serverStatus;
	//                if (!cursor.isNull(SERVER_STATUS_COLUMN_INDEX)) {
	//                    serverStatus = cursor.getInt(SERVER_STATUS_COLUMN_INDEX);
	//                    presenceView.setImageResource(
	//                            Presence.getPresenceIconResourceId(serverStatus));
	//                    presenceView.setVisibility(View.VISIBLE);
	//                } else {
	//                    presenceView.setVisibility(View.GONE);
	//                }
	//            } else {
	//                presenceView.setVisibility(View.GONE);
	//            }
	//
	//            // Set the photo, if requested
	//            if (mDisplayPhotos) {
	//                Bitmap photo = null;
	//
	//                // Look for the cached bitmap
	//                int pos = cursor.getPosition();
	//                SoftReference<Bitmap> ref = mBitmapCache.get(pos);
	//                if (ref != null) {
	//                    photo = ref.get();
	//                }
	//
	//                if (photo == null) {
	//                    // Bitmap cache miss, decode it from the cursor
	//                    if (!cursor.isNull(PHOTO_COLUMN_INDEX)) {
	//                        try {
	//                            byte[] photoData = cursor.getBlob(PHOTO_COLUMN_INDEX);
	//                            photo = BitmapFactory.decodeByteArray(photoData, 0,
	//                                    photoData.length);
	//                            mBitmapCache.put(pos, new SoftReference<Bitmap>(photo));
	//                        } catch (OutOfMemoryError e) {
	//                            // Not enough memory for the photo, use the default one instead
	//                            photo = null;
	//                        }
	//                    }
	//                }
	//
	//                // Bind the photo, or use the fallback no photo resource
	//                if (photo != null) {
	//                    cache.photoView.setImageBitmap(photo);
	//                } else {
	//                    cache.photoView.setImageResource(R.drawable.ic_contact_list_picture);
	//                }
	//            }
            } else {
            	// The "25 more" list item
            	if (convertView == null)
            		view = mInflater.inflate(R.layout.more_threads_view, null);
            	else
            		view = convertView;
            	final Button nextButton = (Button) view.findViewById(R.id.next_button);
            	final Button previousButton = (Button) view.findViewById(R.id.previous_button);
            	if (mAfter != null) {
            		nextButton.setVisibility(View.VISIBLE);
            		nextButton.setOnClickListener(downloadAfterOnClickListener);
            	} else {
            		nextButton.setVisibility(View.INVISIBLE);
            	}
            	if (mBefore != null && mCount != Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT) {
            		previousButton.setVisibility(View.VISIBLE);
            		previousButton.setOnClickListener(downloadBeforeOnClickListener);
            	} else {
            		previousButton.setVisibility(View.INVISIBLE);
            	}
            }
            return view;
        }
    }
    
    /**
     * Jump to thread whose id is mJumpToThreadId. Then clear mJumpToThreadId.
     */
    private void jumpToThread() {
    	if (mJumpToThreadId == null || mThreadsAdapter == null)
    		return;
		for (int k = 0; k < mThreadsAdapter.getCount(); k++) {
			if (mJumpToThreadId.equals(mThreadsAdapter.getItem(k).getId())) {
				getListView().setSelection(k);
				mJumpToThreadId = null;
				break;
			}
		}
    }
    
    
    /**
     * Called when user clicks an item in the list. Starts an activity to
     * open the url for that item.
     */
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        ThreadInfo item = mThreadsAdapter.getItem(position);
        
        // if mThreadsAdapter.getCount() - 1 contains the "next 25, prev 25" buttons,
        // or if there are fewer than 25 threads...
        if (position < mThreadsAdapter.getCount() - 1 || mThreadsAdapter.getCount() < Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT + 1) {
            if (mSettings.onClickAction.equals(Constants.PREF_ON_CLICK_OPEN_LINK))
        	{
                // Mark the thread as selected
                mVoteTargetThreadInfo = item;
                mJumpToThreadId = item.getId();
                //showDialog(Constants.DIALOG_THING_CLICK);
                if (!mVoteTargetThreadInfo.isSelfPost()) {
                    Common.launchBrowser(item.getURL(), RedditIsFun.this);
                }
                else {
                    Intent i = new Intent(getApplicationContext(), CommentsListActivity.class);
                    i.putExtra(ThreadInfo.SUBREDDIT, mVoteTargetThreadInfo.getSubreddit());
                    i.putExtra(ThreadInfo.ID, mVoteTargetThreadInfo.getId());
                    i.putExtra(ThreadInfo.TITLE, mVoteTargetThreadInfo.getTitle());
                    i.putExtra(ThreadInfo.NUM_COMMENTS, Integer.valueOf(mVoteTargetThreadInfo.getNumComments()));
                    startActivity(i);
                }
            } else
            {
                onLongListItemClick(v, position, id);
            }
        } else {
        	// 25 more. Use buttons.
        }
    }

    protected void onLongListItemClick(View v, int position, long id)
    {
    	ThreadInfo item = mThreadsAdapter.getItem(position);
    
	    // if mThreadsAdapter.getCount() - 1 contains the "next 25, prev 25" buttons,
	    // or if there are fewer than 25 threads...
	    if (position < mThreadsAdapter.getCount() - 1 || mThreadsAdapter.getCount() < Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT + 1) {
	        // Mark the thread as selected
	        mVoteTargetThreadInfo = item;
	        mJumpToThreadId = item.getId();
	        showDialog(Constants.DIALOG_THING_CLICK);
	    } else {
	    	// 25 more. Use buttons.
	    }
    }

    /**
     * Resets the output UI list contents, retains session state.
     * @param threadsAdapter A ThreadsListAdapter to use. Pass in null if you want a new empty one created.
     */
    void resetUI(ThreadsListAdapter threadsAdapter) {
    	if (threadsAdapter == null) {
            // Reset the list to be empty.
	    	mThreadsList = new ArrayList<ThreadInfo>();
			mThreadsAdapter = new ThreadsListAdapter(this, mThreadsList);
    	} else {
    		mThreadsAdapter = threadsAdapter;
    	}
	    setListAdapter(mThreadsAdapter);
	    mThreadsAdapter.notifyDataSetChanged();  // Just in case
	    Common.updateListDrawables(this, mSettings.theme);
    }
    
    void resetUrlToGetHere() {
    	mUrlToGetHere = null;
    	mUrlToGetHereChanged = true;
    	mCount = Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT;
    }

    /**
     * Given a subreddit name string, starts the threadlist-download-thread going.
     * 
     * @param subreddit The name of a subreddit ("reddit.com", "gaming", etc.)
     *        If the number of elements in subreddit is >= 2, treat 2nd element as "after" 
     */
    private class DownloadThreadsTask extends AsyncTask<CharSequence, Integer, Boolean> {
    	
    	private ArrayList<ThreadInfo> mThreadInfos = new ArrayList<ThreadInfo>();
    	private String _mUserError = "Error retrieving subreddit info.";
    	
    	public Boolean doInBackground(CharSequence... subreddit) {
    		HttpEntity entity = null;
	    	try {
	    		String url;
	    		StringBuilder sb;
	    		// If refreshing or something, use the previously used URL to get the threads.
	    		// Picking a new subreddit will erase the saved URL, getting rid of after= and before=.
	    		// subreddit.length != 1 means you are going Next or Prev, which creates new URL.
	    		if (subreddit.length == 1 && !mUrlToGetHereChanged && mUrlToGetHere != null) {
	    			url = mUrlToGetHere.toString();
	    		} else {
		    		if (Constants.FRONTPAGE_STRING.equals(subreddit[0])) {
		    			sb = new StringBuilder("http://www.reddit.com/").append(mSortByUrl)
		    				.append(".json?").append(mSortByUrlExtra).append("&");
		    		} else {
		    			sb = new StringBuilder("http://www.reddit.com/r/")
	            			.append(subreddit[0].toString().trim())
	            			.append("/").append(mSortByUrl).append(".json?")
	            			.append(mSortByUrlExtra).append("&");
		    		}
	    			// "before" always comes back null unless you provide correct "count"
		    		if (subreddit.length == 2) {
		    			// count: 25, 50, ...
	    				sb = sb.append("count=").append(mCount)
	    					.append("&after=").append(subreddit[1]).append("&");
	    				mCount += Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT;
		    		}
		    		else if (subreddit.length == 3) {
		    			// count: nothing, 26, 51, ...
		    			sb = sb.append("count=").append(mCount + 1 - Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT)
		    				.append("&before=").append(subreddit[2]).append("&");
		    			mCount -= Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT;
		    		}
	
		    		mUrlToGetHere = url = sb.toString();
		    		mUrlToGetHereChanged = false;
	    		}
	    		
    			HttpGet request = new HttpGet(url);
            	HttpResponse response = mClient.execute(request);
            	entity = response.getEntity();
            	InputStream in = entity.getContent();
                try {
                	parseSubredditJSON(in);
                	in.close();
                	entity.consumeContent();
                	mSettings.setSubreddit(subreddit[0]);
                	return true;
                } catch (IllegalStateException e) {
                	_mUserError = "Invalid subreddit.";
                	if (Constants.LOGGING) Log.e(TAG, e.getMessage());
                } catch (Exception e) {
                	if (Constants.LOGGING) Log.e(TAG, e.getMessage());
                	if (entity != null) {
                		try {
                			entity.consumeContent();
                		} catch (Exception e2) {
                			// Ignore.
                		}
                	}
                }
            } catch (IOException e) {
            	if (Constants.LOGGING) Log.e(TAG, "failed:" + e.getMessage());
            }
            return false;
	    }
    	
    	private void parseSubredditJSON(InputStream in) throws IOException,
		    	JsonParseException, IllegalStateException {
		
			JsonParser jp = jsonFactory.createJsonParser(in);
			
			if (jp.nextToken() == JsonToken.VALUE_NULL)
				return;
			
			// --- Validate initial stuff, skip to the JSON List of threads ---
			String genericListingError = "Not a subreddit listing";
		//	if (JsonToken.START_OBJECT != jp.nextToken()) // starts with "{"
		//		throw new IllegalStateException(genericListingError);
			jp.nextToken();
			if (!Constants.JSON_KIND.equals(jp.getCurrentName()))
				throw new IllegalStateException(genericListingError);
			jp.nextToken();
			if (!Constants.JSON_LISTING.equals(jp.getText()))
				throw new IllegalStateException(genericListingError);
			jp.nextToken();
			if (!Constants.JSON_DATA.equals(jp.getCurrentName()))
				throw new IllegalStateException(genericListingError);
			jp.nextToken();
			if (JsonToken.START_OBJECT != jp.getCurrentToken())
				throw new IllegalStateException(genericListingError);
			jp.nextToken();
			// Save the modhash
			if (!Constants.JSON_MODHASH.equals(jp.getCurrentName()))
				throw new IllegalStateException(genericListingError);
			jp.nextToken();
			if (Constants.EMPTY_STRING.equals(jp.getText()))
				mSettings.setModhash(null);
			else
				mSettings.setModhash(jp.getText());
			jp.nextToken();
			if (!Constants.JSON_CHILDREN.equals(jp.getCurrentName()))
				throw new IllegalStateException(genericListingError);
			jp.nextToken();
			if (jp.getCurrentToken() != JsonToken.START_ARRAY)
				throw new IllegalStateException(genericListingError);
			
			// --- Main parsing ---
			int progressIndex = 0;
			while (jp.nextToken() != JsonToken.END_ARRAY) {
				if (jp.getCurrentToken() != JsonToken.START_OBJECT)
					throw new IllegalStateException("Unexpected non-JSON-object in the children array");
			
				// Process JSON representing one thread
				ThreadInfo ti = new ThreadInfo();
				while (jp.nextToken() != JsonToken.END_OBJECT) {
					String fieldname = jp.getCurrentName();
					jp.nextToken(); // move to value, or START_OBJECT/START_ARRAY
				
					if (Constants.JSON_KIND.equals(fieldname)) {
						if (!Constants.THREAD_KIND.equals(jp.getText())) {
							// Skip this JSON Object since it doesn't represent a thread.
							// May encounter nested objects too.
							int nested = 0;
							for (;;) {
								jp.nextToken();
								if (jp.getCurrentToken() == JsonToken.END_OBJECT && nested == 0)
									break;
								if (jp.getCurrentToken() == JsonToken.START_OBJECT)
									nested++;
								if (jp.getCurrentToken() == JsonToken.END_OBJECT)
									nested--;
							}
							break;  // Go on to the next thread (JSON Object) in the JSON Array.
						}
						ti.put(Constants.JSON_KIND, Constants.THREAD_KIND);
					} else if (Constants.JSON_DATA.equals(fieldname)) { // contains an object
						while (jp.nextToken() != JsonToken.END_OBJECT) {
							String namefield = jp.getCurrentName();
							jp.nextToken(); // move to value
							// Should validate each field but I'm lazy
							if (Constants.JSON_MEDIA.equals(namefield) && jp.getCurrentToken() == JsonToken.START_OBJECT) {
								while (jp.nextToken() != JsonToken.END_OBJECT) {
									String mediaNamefield = jp.getCurrentName();
									jp.nextToken(); // move to value
									ti.put("media/"+mediaNamefield, jp.getText());
								}
							} else if (Constants.JSON_MEDIA_EMBED.equals(namefield) && jp.getCurrentToken() == JsonToken.START_OBJECT) {
								while (jp.nextToken() != JsonToken.END_OBJECT) {
									String mediaNamefield = jp.getCurrentName();
									jp.nextToken(); // move to value
									ti.put("media_embed/"+mediaNamefield, jp.getText());
								}
							} else {
								ti.put(namefield, StringEscapeUtils.unescapeHtml(jp.getText().replaceAll("\r", "")));
							}
						}
					} else {
						throw new IllegalStateException("Unrecognized field '"+fieldname+"'!");
					}
				}
				mThreadInfos.add(ti);
				publishProgress(progressIndex++);
			}
			// Get the "after"
			jp.nextToken();
			if (!Constants.JSON_AFTER.equals(jp.getCurrentName()))
				throw new IllegalStateException(genericListingError);
			jp.nextToken();
			mAfter = jp.getText();
			if (Constants.NULL_STRING.equals(mAfter))
				mAfter = null;
			// Get the "before"
			jp.nextToken();
			if (!Constants.JSON_BEFORE.equals(jp.getCurrentName()))
				throw new IllegalStateException(genericListingError);
			jp.nextToken();
			mBefore = jp.getText();
			if (Constants.NULL_STRING.equals(mBefore))
				mBefore = null;
    	}
    	
    	@Override
    	public void onPreExecute() {
    		synchronized (mCurrentDownloadThreadsTaskLock) {
	    		if (mCurrentDownloadThreadsTask != null)
	    			mCurrentDownloadThreadsTask.cancel(true);
	    		mCurrentDownloadThreadsTask = this;
    		}
    		resetUI(null);
    		mThreadsAdapter.mIsLoading = true;
    		// In case a ReadCacheTask tries to preempt this DownloadThreadsTask
    		mShouldUseThreadsCache = false;
			
	    	if ("jailbait".equals(mSettings.subreddit.toString())) {
	    		Toast lodToast = Toast.makeText(RedditIsFun.this, "", Toast.LENGTH_LONG);
	    		View lodView = ((LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE))
	    			.inflate(R.layout.look_of_disapproval_view, null);
	    		lodToast.setView(lodView);
	    		lodToast.show();
	    	}
	    	showDialog(Constants.DIALOG_LOADING_THREADS_LIST);
	    	if (Constants.FRONTPAGE_STRING.equals(mSettings.subreddit))
	    		setTitle("reddit.com: what's new online!");
	    	else
	    		setTitle("/r/"+mSettings.subreddit.toString().trim());
    	}
    	
    	@Override
    	public void onPostExecute(Boolean success) {
    		synchronized (mCurrentDownloadThreadsTaskLock) {
    			mCurrentDownloadThreadsTask = null;
    		}
    		dismissDialog(Constants.DIALOG_LOADING_THREADS_LIST);
    		if (success) {
	    		for (ThreadInfo ti : mThreadInfos)
	        		mThreadsList.add(ti);
	    		// "25 more" button.
	    		if (mThreadsList.size() >= Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT)
	    			mThreadsList.add(new ThreadInfo());
	    		// Remember this time for caching purposes
	    		mLastRefreshTime = System.currentTimeMillis();
	    		mShouldUseThreadsCache = true;
	    		resetUI(new ThreadsListAdapter(RedditIsFun.this, mThreadsList));
	    		// Point the list to last thread user was looking at, if any
	    		jumpToThread();
    		} else {
    			if (!isCancelled())
    				Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, RedditIsFun.this);
    		}
    	}
    	
    	@Override
    	public void onProgressUpdate(Integer... progress) {
    		mLoadingThreadsProgress.setProgress(progress[0]);
    	}
    }
    
    
    private class LoginTask extends AsyncTask<Void, Void, String> {
    	private CharSequence mUsername, mPassword;
    	
    	LoginTask(CharSequence username, CharSequence password) {
    		mUsername = username;
    		mPassword = password;
    	}
    	
    	@Override
    	public String doInBackground(Void... v) {
    		return Common.doLogin(mUsername, mPassword, mClient, mSettings);
        }
    	
    	@Override
    	protected void onPreExecute() {
    		showDialog(Constants.DIALOG_LOGGING_IN);
    	}
    	
    	@Override
    	protected void onPostExecute(String errorMessage) {
    		dismissDialog(Constants.DIALOG_LOGGING_IN);
    		if (errorMessage == null) {
    			Toast.makeText(RedditIsFun.this, "Logged in as "+mUsername, Toast.LENGTH_SHORT).show();
    			// Check mail
    			new Common.PeekEnvelopeTask(getApplicationContext(), mClient, mSettings.mailNotificationStyle).execute();
    			// Refresh the threads list
    			new DownloadThreadsTask().execute(mSettings.subreddit);
        	} else {
            	Common.showErrorToast(errorMessage, Toast.LENGTH_LONG, RedditIsFun.this);
    		}
    	}
    }
    
    
    private class VoteTask extends AsyncTask<Void, Void, Boolean> {
    	
    	private static final String TAG = "VoteWorker";
    	
    	private CharSequence _mThingFullname, _mSubreddit;
    	private int _mDirection;
    	private String _mUserError = "Error voting.";
    	private ThreadInfo _mTargetThreadInfo;
    	
    	// Save the previous arrow and score in case we need to revert
    	private int _mPreviousScore;
    	private String _mPreviousLikes;
    	
    	VoteTask(CharSequence thingFullname, int direction, CharSequence subreddit) {
    		_mThingFullname = thingFullname;
    		_mDirection = direction;
    		_mSubreddit = subreddit;
    		// Copy these because they can change while voting thread is running
    		_mTargetThreadInfo = mVoteTargetThreadInfo;
    	}
    	
    	@Override
    	public Boolean doInBackground(Void... v) {
        	String status = "";
        	HttpEntity entity = null;
        	
        	if (!mSettings.loggedIn) {
        		_mUserError = "You must be logged in to vote.";
        		return false;
        	}
        	
        	// Update the modhash if necessary
        	if (mSettings.modhash == null) {
        		CharSequence modhash = Common.doUpdateModhash(mClient);
        		if (modhash == null) {
        			// doUpdateModhash should have given an error about credentials
        			Common.doLogout(mSettings, mClient);
        			if (Constants.LOGGING) Log.e(TAG, "Vote failed because doUpdateModhash() failed");
        			return false;
        		}
        		mSettings.setModhash(modhash);
        	}
        	
        	try {
        		// Construct data
    			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
    			nvps.add(new BasicNameValuePair("id", _mThingFullname.toString()));
    			nvps.add(new BasicNameValuePair("dir", String.valueOf(_mDirection)));
    			nvps.add(new BasicNameValuePair("r", _mSubreddit.toString()));
    			nvps.add(new BasicNameValuePair("uh", mSettings.modhash.toString()));
    			// Votehash is currently unused by reddit 
//    				nvps.add(new BasicNameValuePair("vh", "0d4ab0ffd56ad0f66841c15609e9a45aeec6b015"));
    			
    			HttpPost httppost = new HttpPost("http://www.reddit.com/api/vote");
    	        httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
    	        
    	        if (Constants.LOGGING) Log.d(TAG, nvps.toString());
    	        
                // Perform the HTTP POST request
    	    	HttpResponse response = mClient.execute(httppost);
    	    	status = response.getStatusLine().toString();
            	if (!status.contains("OK")) {
            		_mUserError = "HTTP error when voting. Try again.";
            		throw new HttpException(status);
            	}
            	
            	entity = response.getEntity();

            	BufferedReader in = new BufferedReader(new InputStreamReader(entity.getContent()));
            	String line = in.readLine();
            	in.close();
            	if (line == null || Constants.EMPTY_STRING.equals(line)) {
            		_mUserError = "Connection error when voting. Try again.";
            		throw new HttpException("No content returned from vote POST");
            	}
            	if (line.contains("WRONG_PASSWORD")) {
            		_mUserError = "Wrong password.";
            		throw new Exception("Wrong password.");
            	}
            	if (line.contains("USER_REQUIRED")) {
            		// The modhash probably expired
            		throw new Exception("User required. Huh?");
            	}
            	
            	if (Constants.LOGGING) Common.logDLong(TAG, line);
            	
            	entity.consumeContent();
            	return true;
            	
        	} catch (Exception e) {
        		if (entity != null) {
        			try {
        				entity.consumeContent();
        			} catch (Exception e2) {
        				if (Constants.LOGGING) Log.e(TAG, e2.getMessage());
        			}
        		}
        		if (Constants.LOGGING) Log.e(TAG, e.getMessage());
        	}
        	return false;
        }
    	
    	@Override
    	public void onPreExecute() {
    		if (!mSettings.loggedIn) {
        		Common.showErrorToast("You must be logged in to vote.", Toast.LENGTH_LONG, RedditIsFun.this);
        		cancel(true);
        		return;
        	}
        	if (_mDirection < -1 || _mDirection > 1) {
        		if (Constants.LOGGING) Log.e(TAG, "WTF: _mDirection = " + _mDirection);
        		throw new RuntimeException("How the hell did you vote something besides -1, 0, or 1?");
        	}
        	String newScore;
        	String newLikes;
        	_mPreviousScore = Integer.valueOf(_mTargetThreadInfo.getScore());
        	_mPreviousLikes = _mTargetThreadInfo.getLikes();
        	if (Constants.TRUE_STRING.equals(_mPreviousLikes)) {
        		if (_mDirection == 0) {
        			newScore = String.valueOf(_mPreviousScore - 1);
        			newLikes = Constants.NULL_STRING;
        		} else if (_mDirection == -1) {
        			newScore = String.valueOf(_mPreviousScore - 2);
        			newLikes = Constants.FALSE_STRING;
        		} else {
        			cancel(true);
        			return;
        		}
        	} else if (Constants.FALSE_STRING.equals(_mPreviousLikes)) {
        		if (_mDirection == 1) {
        			newScore = String.valueOf(_mPreviousScore + 2);
        			newLikes = Constants.TRUE_STRING;
        		} else if (_mDirection == 0) {
        			newScore = String.valueOf(_mPreviousScore + 1);
        			newLikes = Constants.NULL_STRING;
        		} else {
        			cancel(true);
        			return;
        		}
        	} else {
        		if (_mDirection == 1) {
        			newScore = String.valueOf(_mPreviousScore + 1);
        			newLikes = Constants.TRUE_STRING;
        		} else if (_mDirection == -1) {
        			newScore = String.valueOf(_mPreviousScore - 1);
        			newLikes = Constants.FALSE_STRING;
        		} else {
        			cancel(true);
        			return;
        		}
        	}
    		_mTargetThreadInfo.setLikes(newLikes);
    		_mTargetThreadInfo.setScore(newScore);
    		mThreadsAdapter.notifyDataSetChanged();
    	}
    	
    	@Override
    	public void onPostExecute(Boolean success) {
    		if (!success) {
    			// Vote failed. Undo the arrow and score.
            	int oldImageResourceUp, oldImageResourceDown;
        		if (Constants.TRUE_STRING.equals(_mPreviousLikes)) {
            		oldImageResourceUp = R.drawable.vote_up_red;
            		oldImageResourceDown = R.drawable.vote_down_gray;
            	} else if (Constants.FALSE_STRING.equals(_mPreviousLikes)) {
            		oldImageResourceUp = R.drawable.vote_up_gray;
            		oldImageResourceDown = R.drawable.vote_down_blue;
            	} else {
            		oldImageResourceUp = R.drawable.vote_up_gray;
            		oldImageResourceDown = R.drawable.vote_down_gray;
            	}
        		_mTargetThreadInfo.setLikes(_mPreviousLikes);
        		_mTargetThreadInfo.setScore(String.valueOf(_mPreviousScore));
        		mThreadsAdapter.notifyDataSetChanged();
        		
    			Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, RedditIsFun.this);
    		}
    	}
    }
    
    
    /**
     * Populates the menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.subreddit, menu);
        return true;
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // This happens when the user begins to hold down the menu key, so
        // allow them to chord to get a shortcut.
        mCanChord = true;

    	super.onPrepareOptionsMenu(menu);
    	
    	MenuItem src, dest;
    	
        // Login/Logout
    	if (mSettings.loggedIn) {
	        menu.findItem(R.id.login_logout_menu_id).setTitle(
	        		getResources().getString(R.string.logout)+": " + mSettings.username);
	        menu.findItem(R.id.inbox_menu_id).setVisible(true);
    	} else {
            menu.findItem(R.id.login_logout_menu_id).setTitle(getResources().getString(R.string.login));
            menu.findItem(R.id.inbox_menu_id).setVisible(false);
    	}
    	
    	// Theme: Light/Dark
    	src = mSettings.theme == R.style.Reddit_Light ?
        		menu.findItem(R.id.dark_menu_id) :
        			menu.findItem(R.id.light_menu_id);
        dest = menu.findItem(R.id.light_dark_menu_id);
        dest.setTitle(src.getTitle());
        
        // Sort
        if (Constants.ThreadsSort.SORT_BY_HOT_URL.equals(mSortByUrl))
        	src = menu.findItem(R.id.sort_by_hot_menu_id);
        else if (Constants.ThreadsSort.SORT_BY_NEW_URL.equals(mSortByUrl))
        	src = menu.findItem(R.id.sort_by_new_menu_id);
        else if (Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_URL.equals(mSortByUrl))
        	src = menu.findItem(R.id.sort_by_controversial_menu_id);
        else if (Constants.ThreadsSort.SORT_BY_TOP_URL.equals(mSortByUrl))
        	src = menu.findItem(R.id.sort_by_top_menu_id);
        dest = menu.findItem(R.id.sort_by_menu_id);
        dest.setTitle(src.getTitle());
    	
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!mCanChord) {
            // The user has already fired a shortcut with this hold down of the
            // menu key.
            return false;
        }
        
        switch (item.getItemId()) {
        case R.id.pick_subreddit_menu_id:
    		Intent pickSubredditIntent = new Intent(getApplicationContext(), PickSubredditActivity.class);
    		startActivityForResult(pickSubredditIntent, Constants.ACTIVITY_PICK_SUBREDDIT);
    		break;
    	case R.id.login_logout_menu_id:
        	if (mSettings.loggedIn) {
        		Common.doLogout(mSettings, mClient);
        		Toast.makeText(this, "You have been logged out.", Toast.LENGTH_SHORT).show();
        		new DownloadThreadsTask().execute(mSettings.subreddit);
        	} else {
        		showDialog(Constants.DIALOG_LOGIN);
        	}
    		break;
    	case R.id.refresh_menu_id:
    		// Reset some navigation
    		mUrlToGetHere = null;
    		mUrlToGetHereChanged = true;
    		mAfter = null;
    		mBefore = null;
    		mCount = Constants.DEFAULT_THREAD_DOWNLOAD_LIMIT;
    		new DownloadThreadsTask().execute(mSettings.subreddit);
    		break;
    	case R.id.submit_link_menu_id:
    		Intent submitLinkIntent = new Intent(getApplicationContext(), SubmitLinkActivity.class);
    		submitLinkIntent.putExtra(ThreadInfo.SUBREDDIT, mSettings.subreddit);
    		startActivityForResult(submitLinkIntent, Constants.ACTIVITY_SUBMIT_LINK);
    		break;
    	case R.id.sort_by_menu_id:
    		showDialog(Constants.DIALOG_SORT_BY);
    		break;
    	case R.id.open_browser_menu_id:
    		String url;
    		if (mSettings.subreddit.equals(Constants.FRONTPAGE_STRING))
    			url = "http://www.reddit.com";
    		else
        		url = new StringBuilder("http://www.reddit.com/r/").append(mSettings.subreddit).toString();
    		Common.launchBrowser(url, this);
    		break;
        case R.id.light_dark_menu_id:
    		if (mSettings.theme == R.style.Reddit_Light) {
    			mSettings.setTheme(R.style.Reddit_Dark);
    		} else {
    			mSettings.setTheme(R.style.Reddit_Light);
    		}
    		setTheme(mSettings.theme);
    		setContentView(R.layout.threads_list_content);
    		setListAdapter(mThreadsAdapter);
    		Common.updateListDrawables(this, mSettings.theme);
    		break;
        case R.id.inbox_menu_id:
        	Intent inboxIntent = new Intent(getApplicationContext(), InboxActivity.class);
        	startActivity(inboxIntent);
        	break;
//        case R.id.user_profile_menu_id:
//        	Intent profileIntent = new Intent(getApplicationContext(), UserActivity.class);
//        	startActivity(profileIntent);
//        	break;
    	case R.id.preferences_menu_id:
            Intent prefsIntent = new Intent(getApplicationContext(), RedditPreferencesPage.class);
            startActivity(prefsIntent);
            break;

    	default:
    		throw new IllegalArgumentException("Unexpected action value "+item.getItemId());
    	}
    	
        return true;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
    	Dialog dialog;
    	ProgressDialog pdialog;
    	AlertDialog.Builder builder;
    	LayoutInflater inflater;
    	
    	switch (id) {
    	case Constants.DIALOG_LOGIN:
    		dialog = new Dialog(this);
    		dialog.setContentView(R.layout.login_dialog);
    		dialog.setTitle("Login to reddit.com");
    		final EditText loginUsernameInput = (EditText) dialog.findViewById(R.id.login_username_input);
    		final EditText loginPasswordInput = (EditText) dialog.findViewById(R.id.login_password_input);
    		loginUsernameInput.setOnKeyListener(new OnKeyListener() {
    			public boolean onKey(View v, int keyCode, KeyEvent event) {
    		        if ((event.getAction() == KeyEvent.ACTION_DOWN)
    		        		&& (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB)) {
    		        	loginPasswordInput.requestFocus();
    		        	return true;
    		        }
    		        return false;
    		    }
    		});
    		loginPasswordInput.setOnKeyListener(new OnKeyListener() {
    			public boolean onKey(View v, int keyCode, KeyEvent event) {
    		        if ((event.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
        				CharSequence user = loginUsernameInput.getText().toString().trim();
        				CharSequence password = loginPasswordInput.getText();
        				dismissDialog(Constants.DIALOG_LOGIN);
    		        	new LoginTask(user, password).execute(); 
    		        	return true;
    		        }
    		        return false;
    		    }
    		});
    		final Button loginButton = (Button) dialog.findViewById(R.id.login_button);
    		loginButton.setOnClickListener(new OnClickListener() {
    			public void onClick(View v) {
    				CharSequence user = loginUsernameInput.getText().toString().trim();
    				CharSequence password = loginPasswordInput.getText();
    				dismissDialog(Constants.DIALOG_LOGIN);
    				new LoginTask(user, password).execute();
    		    }
    		});
    		break;
    		
    	case Constants.DIALOG_THING_CLICK:
    		inflater = (LayoutInflater)this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    		builder = new AlertDialog.Builder(this);
    		dialog = builder.setView(inflater.inflate(R.layout.thread_click_dialog, null)).create();
    		break;
    		
    	case Constants.DIALOG_SORT_BY:
    		builder = new AlertDialog.Builder(this);
    		builder.setTitle("Sort by:");
    		builder.setSingleChoiceItems(Constants.ThreadsSort.SORT_BY_CHOICES, 0, new DialogInterface.OnClickListener() {
    			public void onClick(DialogInterface dialog, int item) {
    				dismissDialog(Constants.DIALOG_SORT_BY);
    				CharSequence itemCS = Constants.ThreadsSort.SORT_BY_CHOICES[item];
    				if (Constants.ThreadsSort.SORT_BY_HOT.equals(itemCS)) {
    					mSortByUrl = Constants.ThreadsSort.SORT_BY_HOT_URL;
    					mSortByUrlExtra = Constants.EMPTY_STRING;
    					resetUrlToGetHere();
    					new DownloadThreadsTask().execute(mSettings.subreddit);
        			} else if (Constants.ThreadsSort.SORT_BY_NEW.equals(itemCS)) {
    					showDialog(Constants.DIALOG_SORT_BY_NEW);
    				} else if (Constants.ThreadsSort.SORT_BY_CONTROVERSIAL.equals(itemCS)) {
    					showDialog(Constants.DIALOG_SORT_BY_CONTROVERSIAL);
    				} else if (Constants.ThreadsSort.SORT_BY_TOP.equals(itemCS)) {
    					showDialog(Constants.DIALOG_SORT_BY_TOP);
    				}
    			}
    		});
    		dialog = builder.create();
    		break;
    	case Constants.DIALOG_SORT_BY_NEW:
    		builder = new AlertDialog.Builder(this);
    		builder.setTitle("what's new");
    		builder.setSingleChoiceItems(Constants.ThreadsSort.SORT_BY_NEW_CHOICES, 0, new DialogInterface.OnClickListener() {
    			public void onClick(DialogInterface dialog, int item) {
    				dismissDialog(Constants.DIALOG_SORT_BY_NEW);
    				mSortByUrl = Constants.ThreadsSort.SORT_BY_NEW_URL;
    				CharSequence itemCS = Constants.ThreadsSort.SORT_BY_NEW_CHOICES[item];
    				if (Constants.ThreadsSort.SORT_BY_NEW_NEW.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_NEW_NEW_URL;
    				else if (Constants.ThreadsSort.SORT_BY_NEW_RISING.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_NEW_RISING_URL;
    				resetUrlToGetHere();
    				new DownloadThreadsTask().execute(mSettings.subreddit);
    			}
    		});
    		dialog = builder.create();
    		break;
    	case Constants.DIALOG_SORT_BY_CONTROVERSIAL:
    		builder = new AlertDialog.Builder(this);
    		builder.setTitle("most controversial");
    		builder.setSingleChoiceItems(Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_CHOICES, 0, new DialogInterface.OnClickListener() {
    			public void onClick(DialogInterface dialog, int item) {
    				dismissDialog(Constants.DIALOG_SORT_BY_CONTROVERSIAL);
    				mSortByUrl = Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_URL;
    				CharSequence itemCS = Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_CHOICES[item];
    				if (Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_HOUR.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_HOUR_URL;
    				else if (Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_DAY.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_DAY_URL;
    				else if (Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_WEEK.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_WEEK_URL;
    				else if (Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_MONTH.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_MONTH_URL;
    				else if (Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_YEAR.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_YEAR_URL;
    				else if (Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_ALL.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_CONTROVERSIAL_ALL_URL;
    				resetUrlToGetHere();
    				new DownloadThreadsTask().execute(mSettings.subreddit);
    			}
    		});
    		dialog = builder.create();
    		break;
    	case Constants.DIALOG_SORT_BY_TOP:
    		builder = new AlertDialog.Builder(this);
    		builder.setTitle("top scoring");
    		builder.setSingleChoiceItems(Constants.ThreadsSort.SORT_BY_TOP_CHOICES, 0, new DialogInterface.OnClickListener() {
    			public void onClick(DialogInterface dialog, int item) {
    				dismissDialog(Constants.DIALOG_SORT_BY_TOP);
    				mSortByUrl = Constants.ThreadsSort.SORT_BY_TOP_URL;
    				CharSequence itemCS = Constants.ThreadsSort.SORT_BY_TOP_CHOICES[item];
    				if (Constants.ThreadsSort.SORT_BY_TOP_HOUR.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_TOP_HOUR_URL;
    				else if (Constants.ThreadsSort.SORT_BY_TOP_DAY.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_TOP_DAY_URL;
    				else if (Constants.ThreadsSort.SORT_BY_TOP_WEEK.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_TOP_WEEK_URL;
    				else if (Constants.ThreadsSort.SORT_BY_TOP_MONTH.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_TOP_MONTH_URL;
    				else if (Constants.ThreadsSort.SORT_BY_TOP_YEAR.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_TOP_YEAR_URL;
    				else if (Constants.ThreadsSort.SORT_BY_TOP_ALL.equals(itemCS))
    					mSortByUrlExtra = Constants.ThreadsSort.SORT_BY_TOP_ALL_URL;
    				resetUrlToGetHere();
    				new DownloadThreadsTask().execute(mSettings.subreddit);
    			}
    		});
    		dialog = builder.create();
    		break;

    	// "Please wait"
    	case Constants.DIALOG_LOGGING_IN:
    		pdialog = new ProgressDialog(this);
    		pdialog.setMessage("Logging in...");
    		pdialog.setIndeterminate(true);
    		pdialog.setCancelable(false);
    		dialog = pdialog;
    		break;
    	case Constants.DIALOG_LOADING_THREADS_LIST:
    		mLoadingThreadsProgress = new AutoResetProgressDialog(this);
    		mLoadingThreadsProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    		mLoadingThreadsProgress.setMessage("Loading subreddit...");
    		mLoadingThreadsProgress.setCancelable(true);
    		dialog = mLoadingThreadsProgress;
    		break;
    	case Constants.DIALOG_LOADING_THREADS_CACHE:
    		pdialog = new ProgressDialog(this);
    		pdialog.setMessage("Loading cached subreddit...");
    		pdialog.setIndeterminate(true);
    		pdialog.setCancelable(true);
    		dialog = pdialog;
    		break;
    	case Constants.DIALOG_LOADING_LOOK_OF_DISAPPROVAL:
    		pdialog = new ProgressDialog(this);
    		pdialog.setIndeterminate(true);
    		pdialog.setCancelable(true);
    		pdialog.requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    		pdialog.setFeatureDrawableResource(Window.FEATURE_INDETERMINATE_PROGRESS, R.drawable.look_of_disapproval);
    		dialog = pdialog;
    		break;
    	
    	default:
    		throw new IllegalArgumentException("Unexpected dialog id "+id);
    	}
    	return dialog;
    }
    
    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
    	super.onPrepareDialog(id, dialog);
    	StringBuilder sb;
    	
    	switch (id) {
    	case Constants.DIALOG_LOGIN:
    		if (mSettings.username != null) {
	    		final TextView loginUsernameInput = (TextView) dialog.findViewById(R.id.login_username_input);
	    		loginUsernameInput.setText(mSettings.username);
    		}
    		final TextView loginPasswordInput = (TextView) dialog.findViewById(R.id.login_password_input);
    		loginPasswordInput.setText("");
    		break;
    		
    	case Constants.DIALOG_THING_CLICK:
    		if (mVoteTargetThreadInfo == null)
    			break;
    		final CheckBox voteUpButton = (CheckBox) dialog.findViewById(R.id.vote_up_button);
    		final CheckBox voteDownButton = (CheckBox) dialog.findViewById(R.id.vote_down_button);
    		final TextView titleView = (TextView) dialog.findViewById(R.id.title);
    		final TextView urlView = (TextView) dialog.findViewById(R.id.url);
    		final TextView submissionStuffView = (TextView) dialog.findViewById(R.id.submissionTime_submitter_subreddit);
    		final Button loginButton = (Button) dialog.findViewById(R.id.login_button);
    		final Button linkButton = (Button) dialog.findViewById(R.id.thread_link_button);
    		final Button commentsButton = (Button) dialog.findViewById(R.id.thread_comments_button);
    		
    		titleView.setText(mVoteTargetThreadInfo.getTitle().replaceAll("\n ", " ").replaceAll(" \n", " ").replaceAll("\n", " "));
    		urlView.setText(mVoteTargetThreadInfo.getURL());
    		sb = new StringBuilder(Util.getTimeAgo(Double.valueOf(mVoteTargetThreadInfo.getCreatedUtc())))
    			.append(" by ").append(mVoteTargetThreadInfo.getAuthor());
            // Show subreddit if user is currently looking at front page
    		if (mSettings.isFrontpage) {
    			sb.append(" to ").append(mVoteTargetThreadInfo.getSubreddit());
    		}
            submissionStuffView.setText(sb);
            
    		// Only show upvote/downvote if user is logged in
    		if (mSettings.loggedIn) {
    			loginButton.setVisibility(View.GONE);
    			voteUpButton.setVisibility(View.VISIBLE);
    			voteDownButton.setVisibility(View.VISIBLE);
    			// Set initial states of the vote buttons based on user's past actions
	    		if (Constants.TRUE_STRING.equals(mVoteTargetThreadInfo.getLikes())) {
	    			// User currenty likes it
	    			voteUpButton.setChecked(true);
	    			voteDownButton.setChecked(false);
	    		} else if (Constants.FALSE_STRING.equals(mVoteTargetThreadInfo.getLikes())) {
	    			// User currently dislikes it
	    			voteUpButton.setChecked(false);
	    			voteDownButton.setChecked(true);
	    		} else {
	    			// User is currently neutral
	    			voteUpButton.setChecked(false);
	    			voteDownButton.setChecked(false);
	    		}
	    		voteUpButton.setOnCheckedChangeListener(voteUpOnCheckedChangeListener);
	    		voteDownButton.setOnCheckedChangeListener(voteDownOnCheckedChangeListener);
    		} else {
    			voteUpButton.setVisibility(View.GONE);
    			voteDownButton.setVisibility(View.GONE);
    			loginButton.setVisibility(View.VISIBLE);
    			loginButton.setOnClickListener(new OnClickListener() {
    				public void onClick(View v) {
    					dismissDialog(Constants.DIALOG_THING_CLICK);
    					showDialog(Constants.DIALOG_LOGIN);
    				}
    			});
    		}

    		// "link" button behaves differently for regular links vs. self posts and links to comments pages (e.g., bestof)
            if (mVoteTargetThreadInfo.isSelfPost()) {
            	// It's a self post. Both buttons do the same thing.
            	linkButton.setEnabled(false);
            } else {
            	final String url = mVoteTargetThreadInfo.getURL();
            	linkButton.setOnClickListener(new OnClickListener() {
    				public void onClick(View v) {
    					dismissDialog(Constants.DIALOG_THING_CLICK);
    					// Launch Intent to goto the URL
    					Common.launchBrowser(url, RedditIsFun.this);
    				}
    			});
            	linkButton.setEnabled(true);
            }
            
            // "comments" button is easy: always does the same thing
            commentsButton.setOnClickListener(new OnClickListener() {
    			public void onClick(View v) {
    				dismissDialog(Constants.DIALOG_THING_CLICK);
    				// Launch an Intent for CommentsListActivity
    				Intent i = new Intent(getApplicationContext(), CommentsListActivity.class);
    				i.putExtra(ThreadInfo.SUBREDDIT, mVoteTargetThreadInfo.getSubreddit());
    				i.putExtra(ThreadInfo.ID, mVoteTargetThreadInfo.getId());
    				i.putExtra(ThreadInfo.TITLE, mVoteTargetThreadInfo.getTitle());
    				i.putExtra(ThreadInfo.NUM_COMMENTS, Integer.valueOf(mVoteTargetThreadInfo.getNumComments()));
    				startActivity(i);
    			}
    		});
    		
    		break;
    		
    	case Constants.DIALOG_LOADING_THREADS_LIST:
    		// FIXME: if different number of threads in reddit.com settings (e.g., 100) it should display that as max instead,
    		// since the JSON returns more than just 25 if that is the case.
    		mLoadingThreadsProgress.setMax(mSettings.threadDownloadLimit);
    		break;
    		
		default:
			// No preparation based on app state is required.
			break;
    	}
    }
    
    private final OnClickListener downloadAfterOnClickListener = new OnClickListener() {
		public void onClick(View v) {
			new DownloadThreadsTask().execute(mSettings.subreddit, mAfter);
		}
	};
	private final OnClickListener downloadBeforeOnClickListener = new OnClickListener() {
		public void onClick(View v) {
			new DownloadThreadsTask().execute(mSettings.subreddit, null, mBefore);
		}
	};
	private final CompoundButton.OnCheckedChangeListener voteUpOnCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
    	public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
	    	dismissDialog(Constants.DIALOG_THING_CLICK);
			if (isChecked) {
				new VoteTask(mVoteTargetThreadInfo.getName(), 1, mVoteTargetThreadInfo.getSubreddit()).execute();
			} else {
				new VoteTask(mVoteTargetThreadInfo.getName(), 0, mVoteTargetThreadInfo.getSubreddit()).execute();
			}
		}
    };
    private final CompoundButton.OnCheckedChangeListener voteDownOnCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
	    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
	    	dismissDialog(Constants.DIALOG_THING_CLICK);
			if (isChecked) {
				new VoteTask(mVoteTargetThreadInfo.getName(), -1, mVoteTargetThreadInfo.getSubreddit()).execute();
			} else {
				new VoteTask(mVoteTargetThreadInfo.getName(), 0, mVoteTargetThreadInfo.getSubreddit()).execute();
			}
		}
    };
    
	
    private class ReadCacheTask extends AsyncTask<Void, Void, Boolean> {
    	@Override
    	public Boolean doInBackground(Void... zzz) {
    		FileInputStream fis = null;
    		ObjectInputStream in = null;
    		if (!mShouldUseThreadsCache)
    			return false;
    		try {
    			// read the time
    			fis = openFileInput(Constants.FILENAME_CACHE_TIME);
    			in = new ObjectInputStream(fis);
    			mLastRefreshTime = in.readLong();
    			in.close();
    			fis.close();
    			
    			// Restore previous session from cache, if the cache isn't too old
    		    if (Common.isFreshCache(mLastRefreshTime)) {
    		    	fis = openFileInput(Constants.FILENAME_SUBREDDIT_CACHE);
        			in = new ObjectInputStream(fis);
        			mAfter = (CharSequence) in.readObject();
        			mBefore = (CharSequence) in.readObject();
        			mCount = in.readInt();
        			mJumpToThreadId = (CharSequence) in.readObject();
        			mSettings.setSubreddit((CharSequence) in.readObject());
        			mSortByUrl = (CharSequence) in.readObject();
        			mSortByUrlExtra = (CharSequence) in.readObject();
        			mThreadsList = (ArrayList<ThreadInfo>) in.readObject();
        			mUrlToGetHere = (CharSequence) in.readObject();
        			mUrlToGetHereChanged = in.readBoolean();
        	
        			return true;
    		    }
    		    // Cache is old
    		    return false;
    		} catch (Exception ex) {
    			if (Constants.LOGGING) Log.e(TAG, ex.getMessage());
        		deleteFile(Constants.FILENAME_SUBREDDIT_CACHE);
        		return false;
    		} finally {
	    		try {
	    			in.close();
	    		} catch (Exception ignore) {}
	    		try {
	    			fis.close();
	    		} catch (Exception ignore) {}
    		}
    	}
    	
    	@Override
    	public void onPreExecute() {
    		synchronized (mCurrentDownloadThreadsTaskLock) {
	    		if (mCurrentDownloadThreadsTask != null)
	    			mCurrentDownloadThreadsTask.cancel(true);
	    		mCurrentDownloadThreadsTask = this;
    		}
    		// If there are comments in the cache, open CommentsListActivity
    		for (String file : fileList()) {
        		if (file.equals(Constants.FILENAME_COMMENTS_CACHE)) {
        			Intent i = new Intent(getApplicationContext(), CommentsListActivity.class);
        			startActivity(i);
        			// Stop reading the cache for subreddit
        			this.cancel(true);
        			return;
        		}
    		}
    		showDialog(Constants.DIALOG_LOADING_THREADS_CACHE);
    	}
    	
    	@Override
    	public void onPostExecute(Boolean success) {
    		if (!isCancelled()) {
	    		dismissDialog(Constants.DIALOG_LOADING_THREADS_CACHE);
	    		if (success) {
	    			// Use the cached threads list
			    	resetUI(new ThreadsListAdapter(RedditIsFun.this, mThreadsList));
			    	// Set the title based on subreddit
			    	if (Constants.FRONTPAGE_STRING.equals(mSettings.subreddit))
			    		setTitle("reddit.com: what's new online!");
			    	else
			    		setTitle("/r/"+mSettings.subreddit.toString().trim());
	    		} else {
	    			//Common.showErrorToast("Reading subreddit cache failed.", Toast.LENGTH_SHORT, RedditIsFun.this);
	    			// Since it didn't read from cache, download normally from Internet.
	    			new DownloadThreadsTask().execute(mSettings.subreddit);
	    		}
    		}
    	}
    }
    

	
	@Override
    protected void onSaveInstanceState(Bundle state) {
    	super.onSaveInstanceState(state);
    	state.putCharSequence(ThreadInfo.SUBREDDIT, mSettings.subreddit);
    	state.putCharSequence(Constants.URL_TO_GET_HERE_KEY, mUrlToGetHere);
    	state.putCharSequence(Constants.ThreadsSort.SORT_BY_KEY, mSortByUrl);
    	state.putCharSequence(Constants.JUMP_TO_THREAD_ID_KEY, mJumpToThreadId);
    	state.putInt(Constants.THREAD_COUNT, mCount);
    	
    	// Cache
		if (mThreadsList == null)
			return;
		FileOutputStream fos = null;
		ObjectOutputStream out = null;
		try {
			// write the time
			fos = openFileOutput(Constants.FILENAME_CACHE_TIME, MODE_PRIVATE);
			out = new ObjectOutputStream(fos);
			out.writeLong(mLastRefreshTime);
			out.close();
			fos.close();
		} catch (IOException ex) {
			if (Constants.LOGGING) Log.e(TAG, ex.getLocalizedMessage());
			deleteFile(Constants.FILENAME_SUBREDDIT_CACHE);
		} finally {
			try {
				out.close();
			} catch (Exception ignore) {}
			try {
				fos.close();
			} catch (Exception ignore) {}			
		}
		
		try {
			// Write cache variables in alphabetical order by variable name.
			fos = openFileOutput(Constants.FILENAME_SUBREDDIT_CACHE, MODE_PRIVATE);
			out = new ObjectOutputStream(fos);
			out.writeObject(mAfter);
			out.writeObject(mBefore);
			out.writeInt(mCount);
			out.writeObject(mJumpToThreadId);
			out.writeObject(mSettings.subreddit);
			out.writeObject(mSortByUrl);
			out.writeObject(mSortByUrlExtra);
			out.writeObject(mThreadsList);
			out.writeObject(mUrlToGetHere);
			out.writeBoolean(mUrlToGetHereChanged);
		} catch (IOException ex) {
			if (Constants.LOGGING) Log.e(TAG, ex.getLocalizedMessage());
			deleteFile(Constants.FILENAME_SUBREDDIT_CACHE);
		} finally {
			try {
				out.close();
			} catch (Exception ignore) {}
			try {
				fos.close();
			} catch (Exception ignore) {}			
		}
    }
    
    /**
     * Called to "thaw" re-animate the app from a previous onSaveInstanceState().
     * 
     * @see android.app.Activity#onRestoreInstanceState
     */
    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        final int[] myDialogs = {
        	Constants.DIALOG_LOADING_LOOK_OF_DISAPPROVAL,
        	Constants.DIALOG_LOADING_THREADS_CACHE,
        	Constants.DIALOG_LOADING_THREADS_LIST,
        	Constants.DIALOG_LOGGING_IN,
        	Constants.DIALOG_LOGIN,
        	Constants.DIALOG_SORT_BY,
        	Constants.DIALOG_SORT_BY_CONTROVERSIAL,
        	Constants.DIALOG_SORT_BY_NEW,
        	Constants.DIALOG_SORT_BY_TOP,
        	Constants.DIALOG_THING_CLICK,
        };
        for (int dialog : myDialogs) {
	        try {
	        	dismissDialog(dialog);
		    } catch (IllegalArgumentException e) {
		    	// Ignore.
		    }
        }
    }
}

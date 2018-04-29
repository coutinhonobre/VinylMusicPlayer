package com.poupa.vinylmusicplayer.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialcab.MaterialCab;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.util.DialogUtils;
import com.github.ksoichiro.android.observablescrollview.ObservableListView;
import com.kabouzeid.appthemehelper.util.ColorUtil;
import com.kabouzeid.appthemehelper.util.MaterialValueHelper;
import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.adapter.album.HorizontalAlbumAdapter;
import com.poupa.vinylmusicplayer.adapter.song.ArtistSongAdapter;
import com.poupa.vinylmusicplayer.dialogs.AddToPlaylistDialog;
import com.poupa.vinylmusicplayer.dialogs.SleepTimerDialog;
import com.poupa.vinylmusicplayer.glide.GlideApp;
import com.poupa.vinylmusicplayer.glide.VinylColoredTarget;
import com.poupa.vinylmusicplayer.glide.VinylGlideExtension;
import com.poupa.vinylmusicplayer.helper.MusicPlayerRemote;
import com.poupa.vinylmusicplayer.interfaces.CabHolder;
import com.poupa.vinylmusicplayer.interfaces.LoaderIds;
import com.poupa.vinylmusicplayer.interfaces.PaletteColorHolder;
import com.poupa.vinylmusicplayer.lastfm.rest.LastFMRestClient;
import com.poupa.vinylmusicplayer.lastfm.rest.model.LastFmArtist;
import com.poupa.vinylmusicplayer.loader.ArtistLoader;
import com.poupa.vinylmusicplayer.misc.SimpleObservableScrollViewCallbacks;
import com.poupa.vinylmusicplayer.misc.WrappedAsyncTaskLoader;
import com.poupa.vinylmusicplayer.model.Artist;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.ui.activities.base.AbsSlidingMusicPanelActivity;
import com.poupa.vinylmusicplayer.util.CustomArtistImageUtil;
import com.poupa.vinylmusicplayer.util.NavigationUtil;
import com.poupa.vinylmusicplayer.util.PreferenceUtil;
import com.poupa.vinylmusicplayer.util.Util;
import com.poupa.vinylmusicplayer.util.VinylMusicPlayerColorUtil;

import java.util.ArrayList;
import java.util.Locale;

import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Be careful when changing things in this Activity!
 */
public class ArtistDetailActivity extends AbsSlidingMusicPanelActivity implements PaletteColorHolder, CabHolder, LoaderManager.LoaderCallbacks<Artist> {

    public static final String TAG = ArtistDetailActivity.class.getSimpleName();
    private static final int LOADER_ID = LoaderIds.ARTIST_DETAIL_ACTIVITY;
    private static final int REQUEST_CODE_SELECT_IMAGE = 1000;

    public static final String EXTRA_ARTIST_ID = "extra_artist_id";

    @BindView(R.id.image)
    ImageView artistImage;
    @BindView(R.id.list_background)
    View songListBackground;
    @BindView(R.id.list)
    ObservableListView songListView;
    @BindView(R.id.title)
    TextView artistName;
    @BindView(R.id.toolbar)
    Toolbar toolbar;

    View songListHeader;
    RecyclerView albumRecyclerView;

    private MaterialCab cab;
    private int headerOffset;
    private int titleViewHeight;
    private int artistImageViewHeight;
    private int toolbarColor;
    private float toolbarAlpha;
    private boolean usePalette;

    private Artist artist;
    @Nullable
    private Spanned biography;
    private MaterialDialog biographyDialog;
    private HorizontalAlbumAdapter albumAdapter;
    private ArtistSongAdapter songAdapter;

    private LastFMRestClient lastFMRestClient;

    private boolean forceDownload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setDrawUnderStatusbar(true);
        ButterKnife.bind(this);

        supportPostponeEnterTransition();

        lastFMRestClient = new LastFMRestClient(this);
        usePalette = PreferenceUtil.getInstance().albumArtistColoredFooters();

        initViews();
        setUpObservableListViewParams();
        setUpViews();
        setUpToolbar();

        getSupportLoaderManager().initLoader(LOADER_ID, getIntent().getExtras(), this);
    }

    @Override
    protected View createContentView() {
        return wrapSlidingMusicPanel(R.layout.activity_artist_detail);
    }

    private final SimpleObservableScrollViewCallbacks observableScrollViewCallbacks = new SimpleObservableScrollViewCallbacks() {
        @Override
        public void onScrollChanged(int scrollY, boolean b, boolean b2) {
            scrollY += artistImageViewHeight + titleViewHeight;
            float flexibleRange = artistImageViewHeight - headerOffset;

            // Translate album cover
            artistImage.setTranslationY(Math.max(-artistImageViewHeight, -scrollY / 2));

            // Translate list background
            songListBackground.setTranslationY(Math.max(0, -scrollY + artistImageViewHeight));

            // Change alpha of overlay
            toolbarAlpha = Math.max(0, Math.min(1, (float) scrollY / flexibleRange));
            toolbar.setBackgroundColor(ColorUtil.withAlpha(toolbarColor, toolbarAlpha));
            setStatusbarColor(ColorUtil.withAlpha(toolbarColor, cab != null && cab.isActive() ? 1 : toolbarAlpha));

            // Translate name text
            int maxTitleTranslationY = artistImageViewHeight;
            int titleTranslationY = maxTitleTranslationY - scrollY;
            titleTranslationY = Math.max(headerOffset, titleTranslationY);

            artistName.setTranslationY(titleTranslationY);
        }
    };

    private void setUpObservableListViewParams() {
        artistImageViewHeight = getResources().getDimensionPixelSize(R.dimen.header_image_height);
        toolbarColor = DialogUtils.resolveColor(this, R.attr.defaultFooterColor);
        int toolbarHeight = Util.getActionBarSize(this);
        titleViewHeight = getResources().getDimensionPixelSize(R.dimen.title_view_height);
        headerOffset = toolbarHeight;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            headerOffset += getResources().getDimensionPixelSize(R.dimen.status_bar_padding);
        }
    }

    private void initViews() {
        songListHeader = LayoutInflater.from(this).inflate(R.layout.artist_detail_header, songListView, false);
        albumRecyclerView = ButterKnife.findById(songListHeader, R.id.recycler_view);
    }

    private void setUpViews() {
        setUpSongListView();
        setUpAlbumRecyclerView();
    }

    private void setUpSongListView() {
        setUpSongListPadding();
        songListView.setScrollViewCallbacks(observableScrollViewCallbacks);
        songListView.addHeaderView(songListHeader);

        songAdapter = new ArtistSongAdapter(this, getArtist().getSongs(), this);
        songListView.setAdapter(songAdapter);

        final View contentView = getWindow().getDecorView().findViewById(android.R.id.content);
        contentView.post(() -> {
            songListBackground.getLayoutParams().height = contentView.getHeight();
            observableScrollViewCallbacks.onScrollChanged(-(artistImageViewHeight + titleViewHeight), false, false);
        });
    }

    private void setUpSongListPadding() {
        songListView.setPadding(0, artistImageViewHeight + titleViewHeight, 0, 0);
    }

    private void setUpAlbumRecyclerView() {
        albumRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        albumAdapter = new HorizontalAlbumAdapter(this, getArtist().albums, usePalette, this);
        albumRecyclerView.setAdapter(albumAdapter);
        albumAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
                if (albumAdapter.getItemCount() == 0) finish();
            }
        });
    }

    protected void setUsePalette(boolean usePalette) {
        albumAdapter.usePalette(usePalette);
        PreferenceUtil.getInstance().setAlbumArtistColoredFooters(usePalette);
        this.usePalette = usePalette;
    }

    private void reload() {
        getSupportLoaderManager().restartLoader(LOADER_ID, getIntent().getExtras(), this);
    }

    private void loadBiography() {
        loadBiography(Locale.getDefault().getLanguage());
    }

    private void loadBiography(@Nullable final String lang) {
        biography = null;

        lastFMRestClient.getApiService()
                .getArtistInfo(getArtist().getName(), lang, null)
                .enqueue(new Callback<LastFmArtist>() {
                    @Override
                    public void onResponse(@NonNull Call<LastFmArtist> call, @NonNull Response<LastFmArtist> response) {
                        final LastFmArtist lastFmArtist = response.body();
                        if (lastFmArtist != null && lastFmArtist.getArtist() != null) {
                            final String bioContent = lastFmArtist.getArtist().getBio().getContent();
                            if (bioContent != null && !bioContent.trim().isEmpty()) {
                                biography = Html.fromHtml(bioContent);
                            }
                        }

                        // If the "lang" parameter is set and no biography is given, retry with default language
                        if (biography == null && lang != null) {
                            loadBiography(null);
                            return;
                        }

                        if (!Util.isAllowedToDownloadMetadata(ArtistDetailActivity.this)) {
                            if (biography != null) {
                                biographyDialog.setContent(biography);
                            } else {
                                biographyDialog.dismiss();
                                Toast.makeText(ArtistDetailActivity.this, getResources().getString(R.string.biography_unavailable), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<LastFmArtist> call, @NonNull Throwable t) {
                        t.printStackTrace();
                        biography = null;
                    }
                });
    }

    private void loadArtistImage() {
        GlideApp.with(this)
                .asBitmapPalette()
                .load(VinylGlideExtension.getArtistModel(artist, forceDownload))
                .transition(VinylGlideExtension.getDefaultTransition())
                .artistOptions(artist)
                .dontAnimate()
                .into(new VinylColoredTarget(artistImage) {
                    @Override
                    public void onColorReady(int color) {
                        setColors(color);
                    }
                });
        forceDownload = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CODE_SELECT_IMAGE:
                if (resultCode == RESULT_OK) {
                    CustomArtistImageUtil.getInstance(this).setCustomArtistImage(artist, data.getData());
                }
                break;
            default:
                if (resultCode == RESULT_OK) {
                    reload();
                }
                break;
        }
    }

    @Override
    public int getPaletteColor() {
        return toolbarColor;
    }

    private void setColors(int color) {
        toolbarColor = color;
        artistName.setBackgroundColor(color);
        artistName.setTextColor(MaterialValueHelper.getPrimaryTextColor(this, ColorUtil.isColorLight(color)));
        setNavigationbarColor(color);
        setTaskDescriptionColor(color);
    }

    private void setUpToolbar() {
        setSupportActionBar(toolbar);
        //noinspection ConstantConditions
        getSupportActionBar().setTitle(null);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_artist_detail, menu);
        menu.findItem(R.id.action_colored_footers).setChecked(usePalette);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        final ArrayList<Song> songs = songAdapter.getDataSet();
        switch (id) {
            case R.id.action_sleep_timer:
                new SleepTimerDialog().show(getSupportFragmentManager(), "SET_SLEEP_TIMER");
                return true;
            case R.id.action_equalizer:
                NavigationUtil.openEqualizer(this);
                return true;
            case R.id.action_shuffle_artist:
                MusicPlayerRemote.openAndShuffleQueue(songs, true);
                return true;
            case R.id.action_play_next:
                MusicPlayerRemote.playNext(songs);
                return true;
            case R.id.action_add_to_current_playing:
                MusicPlayerRemote.enqueue(songs);
                return true;
            case R.id.action_add_to_playlist:
                AddToPlaylistDialog.create(songs).show(getSupportFragmentManager(), "ADD_PLAYLIST");
                return true;
            case android.R.id.home:
                super.onBackPressed();
                return true;
            case R.id.action_biography:
                if (biographyDialog == null) {
                    biographyDialog = new MaterialDialog.Builder(this)
                            .title(artist.getName())
                            .positiveText(android.R.string.ok)
                            .build();
                }
                if (Util.isAllowedToDownloadMetadata(ArtistDetailActivity.this)) { // wiki should've been already downloaded
                    if (biography != null) {
                        biographyDialog.setContent(biography);
                        biographyDialog.show();
                    } else {
                        Toast.makeText(ArtistDetailActivity.this, getResources().getString(R.string.biography_unavailable), Toast.LENGTH_SHORT).show();
                    }
                } else { // force download
                    biographyDialog.show();
                    loadBiography();
                }
                return true;
            case R.id.action_set_artist_image:
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, getString(R.string.pick_from_local_storage)), REQUEST_CODE_SELECT_IMAGE);
                return true;
            case R.id.action_reset_artist_image:
                Toast.makeText(ArtistDetailActivity.this, getResources().getString(R.string.updating), Toast.LENGTH_SHORT).show();
                CustomArtistImageUtil.getInstance(ArtistDetailActivity.this).resetCustomArtistImage(artist);
                forceDownload = true;
                return true;
            case R.id.action_colored_footers:
                item.setChecked(!item.isChecked());
                setUsePalette(item.isChecked());
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @NonNull
    @Override
    public MaterialCab openCab(int menuRes, @NonNull final MaterialCab.Callback callback) {
        if (cab != null && cab.isActive()) cab.finish();
        cab = new MaterialCab(this, R.id.cab_stub)
                .setMenu(menuRes)
                .setCloseDrawableRes(R.drawable.ic_close_white_24dp)
                .setBackgroundColor(VinylMusicPlayerColorUtil.shiftBackgroundColorForLightText(getPaletteColor()))
                .start(new MaterialCab.Callback() {
                    @Override
                    public boolean onCabCreated(MaterialCab materialCab, Menu menu) {
                        setStatusbarColor(ColorUtil.stripAlpha(toolbarColor));
                        return callback.onCabCreated(materialCab, menu);
                    }

                    @Override
                    public boolean onCabItemClicked(MenuItem menuItem) {
                        return callback.onCabItemClicked(menuItem);
                    }

                    @Override
                    public boolean onCabFinished(MaterialCab materialCab) {
                        setStatusbarColor(ColorUtil.withAlpha(toolbarColor, toolbarAlpha));
                        return callback.onCabFinished(materialCab);
                    }
                });
        return cab;
    }

    @Override
    public void onBackPressed() {
        if (cab != null && cab.isActive()) cab.finish();
        else {
            albumRecyclerView.stopScroll();
            super.onBackPressed();
        }
    }

    @Override
    public void onMediaStoreChanged() {
        super.onMediaStoreChanged();
        reload();
    }

    @Override
    public void setStatusbarColor(int color) {
        super.setStatusbarColor(color);
        setLightStatusbar(false);
    }

    private void setArtist(Artist artist) {
        this.artist = artist;
        loadArtistImage();

        if (Util.isAllowedToDownloadMetadata(this)) {
            loadBiography();
        }

        artistName.setText(artist.getName());
        songAdapter.swapDataSet(artist.getSongs());
        albumAdapter.swapDataSet(artist.albums);
    }

    private Artist getArtist() {
        if (artist == null) artist = new Artist();
        return artist;
    }

    @Override
    public Loader<Artist> onCreateLoader(int id, Bundle args) {
        return new AsyncArtistDataLoader(this, args.getInt(EXTRA_ARTIST_ID));
    }

    @Override
    public void onLoadFinished(Loader<Artist> loader, Artist data) {
        supportStartPostponedEnterTransition();
        setArtist(data);
    }

    @Override
    public void onLoaderReset(Loader<Artist> loader) {
        this.artist = new Artist();
        songAdapter.swapDataSet(artist.getSongs());
        albumAdapter.swapDataSet(artist.albums);
    }

    private static class AsyncArtistDataLoader extends WrappedAsyncTaskLoader<Artist> {
        private final int artistId;

        public AsyncArtistDataLoader(Context context, int artistId) {
            super(context);
            this.artistId = artistId;
        }

        @Override
        public Artist loadInBackground() {
            return ArtistLoader.getArtist(getContext(), artistId);
        }
    }
}
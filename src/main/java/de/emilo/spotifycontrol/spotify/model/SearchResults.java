package de.emilo.spotifycontrol.spotify.model;

import java.util.List;

public record SearchResults(String query, List<Track> tracks, List<MediaItem> playlists, List<MediaItem> albums,
                            List<MediaItem> artists) {

    public static final SearchResults EMPTY = new SearchResults("", List.of(), List.of(), List.of(), List.of());

    public boolean isEmpty() {
        return tracks.isEmpty() && playlists.isEmpty() && albums.isEmpty() && artists.isEmpty();
    }
}

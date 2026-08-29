package de.emilo.spotifycontrol.spotify.model;

import java.util.List;

/**
 * One page of a track listing.
 *
 * <p>{@code rawCount} is how many entries the page held before local files and removed titles were
 * dropped, so it is what the next offset has to advance by - counting the usable tracks instead
 * would make the paging skip or repeat entries. {@code total} is the length of the whole listing
 * and is known from the first page onwards, which lets the header show the real count long before
 * every track has been fetched.
 */
public record TrackPage(List<Track> tracks, int rawCount, int total, boolean complete, boolean failed) {

    public static final TrackPage FAILED = new TrackPage(List.of(), 0, 0, true, true);

    public static TrackPage single(List<Track> tracks) {
        return new TrackPage(tracks, tracks.size(), tracks.size(), true, false);
    }
}

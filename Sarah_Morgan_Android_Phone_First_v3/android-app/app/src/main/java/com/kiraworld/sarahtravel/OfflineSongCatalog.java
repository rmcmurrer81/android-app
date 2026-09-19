package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Short public-domain children's sing-alongs for the offline flight companion.
 *
 * Only the old, commonly published first verses are included. Sarah uses an
 * original TTS pitch-and-rhythm treatment instead of copying a modern sound
 * recording or arrangement.
 */
public final class OfflineSongCatalog {
    public static final class Line {
        public final String text;
        public final float pitch;
        public final float rate;

        public Line(String text, float pitch, float rate) {
            this.text = text == null ? "" : text.trim();
            this.pitch = clamp(pitch, 0.65f, 1.45f);
            this.rate = clamp(rate, 0.55f, 1.15f);
        }
    }

    public static final class Song {
        public final String id;
        public final String title;
        public final String rightsNote;
        public final List<Line> lines;

        Song(String id, String title, String rightsNote, List<Line> lines) {
            this.id = id;
            this.title = title;
            this.rightsNote = rightsNote;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    private static final List<Song> SONGS = Collections.unmodifiableList(Arrays.asList(
            new Song(
                    "twinkle",
                    "Twinkle, Twinkle, Little Star",
                    "Jane Taylor's 1806 verse and the traditional 18th-century melody are public domain.",
                    Arrays.asList(
                            line("Twinkle, twinkle, little star,", 1.00f, 0.78f),
                            line("How I wonder what you are.", 1.10f, 0.78f),
                            line("Up above the world so high,", 1.20f, 0.76f),
                            line("Like a diamond in the sky.", 1.10f, 0.76f),
                            line("Twinkle, twinkle, little star,", 1.00f, 0.78f),
                            line("How I wonder what you are.", 0.92f, 0.74f))),
            new Song(
                    "row_boat",
                    "Row, Row, Row Your Boat",
                    "The familiar verse and tune were published in the 19th century and are public domain.",
                    Arrays.asList(
                            line("Row, row, row your boat,", 0.96f, 0.82f),
                            line("Gently down the stream.", 1.04f, 0.78f),
                            line("Merrily, merrily, merrily, merrily,", 1.14f, 0.88f),
                            line("Life is but a dream.", 0.92f, 0.72f))),
            new Song(
                    "mary_lamb",
                    "Mary Had a Little Lamb",
                    "Sarah Josepha Hale's 1830 verse and the traditional melody are public domain.",
                    Arrays.asList(
                            line("Mary had a little lamb,", 1.00f, 0.80f),
                            line("Little lamb, little lamb.", 1.10f, 0.82f),
                            line("Mary had a little lamb,", 1.00f, 0.80f),
                            line("Whose fleece was white as snow.", 0.90f, 0.72f))),
            new Song(
                    "baa_baa",
                    "Baa, Baa, Black Sheep",
                    "The traditional 18th-century nursery rhyme is public domain.",
                    Arrays.asList(
                            line("Baa, baa, black sheep,", 1.00f, 0.80f),
                            line("Have you any wool?", 1.10f, 0.78f),
                            line("Yes sir, yes sir, three bags full.", 1.15f, 0.82f),
                            line("One for the master,", 1.05f, 0.78f),
                            line("One for the dame,", 1.00f, 0.78f),
                            line("And one for the little child who lives down the lane.", 0.92f, 0.76f)))
    ));

    private OfflineSongCatalog() { }

    public static List<Song> all() {
        return SONGS;
    }

    public static Song find(String id) {
        if (id == null) return null;
        for (Song song : SONGS) if (song.id.equalsIgnoreCase(id.trim())) return song;
        return null;
    }

    private static Line line(String text, float pitch, float rate) {
        return new Line(text, pitch, rate);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

public final class TravelNotebookActivity extends Activity {
    private SarahDatabase db;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_notebook);
        db = new SarahDatabase(this);
        container = findViewById(R.id.notebookContainer);
        findViewById(R.id.addWishButton).setOnClickListener(v -> showWishDialog());
        findViewById(R.id.addTripButton).setOnClickListener(v -> showTripDialog());
        refresh();
    }

    private void refresh() {
        container.removeAllViews();
        addHeader("Places I want to visit");
        for (Map<String, String> row : db.listWishes(50)) addRow(row.get("destination"), row.get("notes"));
        addHeader("Trips");
        for (Map<String, String> row : db.listTrips(50)) addRow(row.get("status") + ": " + row.get("destination"), row.get("title") + (row.get("notes").isEmpty() ? "" : " — " + row.get("notes")));
        addHeader("What Sarah remembers");
        for (Map<String, String> row : db.listMemories(100)) addRow(row.get("category"), row.get("summary"));
    }

    private void addHeader(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(20f);
        v.setTextColor(getColor(R.color.sarah_navy));
        v.setPadding(0, 20, 0, 8);
        container.addView(v);
    }

    private void addRow(String title, String detail) {
        TextView v = new TextView(this);
        v.setText("• " + title + (detail == null || detail.isEmpty() ? "" : "\n  " + detail));
        v.setTextSize(16f);
        v.setPadding(4, 8, 4, 8);
        container.addView(v);
    }

    private void showWishDialog() {
        LinearLayout box = dialogBox();
        EditText destination = field("Destination");
        EditText notes = field("Why you want to go or what interests you");
        box.addView(destination); box.addView(notes);
        new AlertDialog.Builder(this).setTitle("Wish-list place").setView(box).setPositiveButton("Save", (d, w) -> {
            if (destination.getText().toString().trim().isEmpty()) { Toast.makeText(this, "Enter a destination.", Toast.LENGTH_SHORT).show(); return; }
            db.addWish(destination.getText().toString(), notes.getText().toString()); refresh();
        }).setNegativeButton("Cancel", null).show();
    }

    private void showTripDialog() {
        LinearLayout box = dialogBox();
        EditText title = field("Trip name");
        EditText destination = field("Destination");
        EditText status = field("past, planned, or current");
        EditText notes = field("Favorite moments, worries, dates, or notes");
        box.addView(title); box.addView(destination); box.addView(status); box.addView(notes);
        new AlertDialog.Builder(this).setTitle("Trip record").setView(box).setPositiveButton("Save", (d, w) -> {
            if (title.getText().toString().trim().isEmpty() || destination.getText().toString().trim().isEmpty()) { Toast.makeText(this, "Enter a name and destination.", Toast.LENGTH_SHORT).show(); return; }
            db.addTrip(title.getText().toString(), destination.getText().toString(), status.getText().toString().trim().isEmpty() ? "planned" : status.getText().toString(), notes.getText().toString()); refresh();
        }).setNegativeButton("Cancel", null).show();
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        return box;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }
}

package com.hemant.contactcopy;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phone ke SAARE accounts (sab Gmail IDs, SIM, phone storage) se contacts padhta hai,
 * same number wale duplicates ko ek bana deta hai, aur ek saaf .vcf / CSV export deta hai.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;
    private static final int REQ_SAVE_VCF = 2;
    private static final int REQ_SAVE_CSV = 3;

    static class Contact {
        String name;
        String number;   // display, +91XXXXXXXXXX
        int copies = 1;  // kitni baar mila (duplicate count)
        @Override public String toString() {
            return name + "\n" + number + (copies > 1 ? "   (" + copies + " duplicate hataye)" : "");
        }
    }

    private final List<Contact> contacts = new ArrayList<>();
    private int rawCount = 0;
    private TextView summary;
    private ArrayAdapter<Contact> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        summary = new TextView(this);
        summary.setTextSize(16);
        summary.setTextColor(Color.parseColor("#2a1420"));
        summary.setText("Contacts padhne ke liye permission do…");
        root.addView(summary);

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(btn("VCF Save", v -> save(REQ_SAVE_VCF, "text/x-vcard", ".vcf")));
        row1.addView(btn("CSV Save", v -> save(REQ_SAVE_CSV, "text/csv", ".csv")));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(btn("Copy All", v -> copyAll()));
        row2.addView(btn("Share VCF", v -> share()));
        root.addView(row2);

        EditText search = new EditText(this);
        search.setHint("Naam ya number dhoondo");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) { adapter.getFilter().filter(s); }
            public void afterTextChanged(Editable e) {}
        });
        root.addView(search);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_PERM);
        } else {
            loadContacts();
        }
    }

    private Button btn(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_PERM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) loadContacts();
        else summary.setText("Permission nahi mili. Settings → Apps → Contact Copy → Permissions → Contacts ON karo.");
    }

    /** Number ko ek key mein badlo: Indian mobile ke liye last 10 digits. */
    static String key(String raw) {
        String d = raw.replaceAll("[^0-9]", "");
        if (d.length() >= 10) return d.substring(d.length() - 10);
        return d;
    }

    static String pretty(String raw) {
        String d = raw.replaceAll("[^0-9+]", "");
        String digits = d.replace("+", "");
        if (d.startsWith("+")) return "+" + digits;
        if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (digits.length() == 10) return "+91" + digits;
        if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
        return digits;
    }

    private void loadContacts() {
        Map<String, Contact> map = new LinkedHashMap<>();
        rawCount = 0;
        String[] proj = {Phone.DISPLAY_NAME, Phone.NUMBER};
        try (Cursor c = getContentResolver().query(Phone.CONTENT_URI, proj, null, null,
                Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    String num = c.getString(1);
                    if (num == null) continue;
                    String k = key(num);
                    if (k.length() < 6) continue;
                    rawCount++;
                    Contact ex = map.get(k);
                    if (ex == null) {
                        Contact n = new Contact();
                        n.name = (name == null || name.trim().isEmpty()) ? pretty(num) : name.trim();
                        n.number = pretty(num);
                        map.put(k, n);
                    } else {
                        ex.copies++;
                        // Jo naam zyada poora ho (lamba) wahi rakho
                        if (name != null && name.trim().length() > ex.name.length()) ex.name = name.trim();
                    }
                }
            }
        }
        contacts.clear();
        contacts.addAll(map.values());
        Collections.sort(contacts, (a, b) -> a.name.compareToIgnoreCase(b.name));
        adapter.clear();
        adapter.addAll(contacts);
        summary.setText("Kul entries: " + rawCount + "\nUnique contacts: " + contacts.size()
                + "\nDuplicate hataye: " + (rawCount - contacts.size()));
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;");
    }

    private String buildVcf() {
        StringBuilder sb = new StringBuilder();
        for (Contact c : contacts) {
            sb.append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
              .append("FN:").append(esc(c.name)).append("\r\n")
              .append("N:;").append(esc(c.name)).append(";;;\r\n")
              .append("TEL;TYPE=CELL:").append(c.number).append("\r\n")
              .append("END:VCARD\r\n");
        }
        return sb.toString();
    }

    private String buildCsv() {
        StringBuilder sb = new StringBuilder("Name,Phone\n");
        for (Contact c : contacts) {
            sb.append('"').append(c.name.replace("\"", "\"\"")).append("\",").append(c.number).append('\n');
        }
        return sb.toString();
    }

    private String fileName(String ext) {
        return "Contacts_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ext;
    }

    private void save(int req, String mime, String ext) {
        if (contacts.isEmpty()) { toast("Koi contact nahi mila"); return; }
        if (Build.VERSION.SDK_INT < 19) { toast("Is Android version par Save nahi chalega, Share use karo"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, fileName(ext));
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String body = req == REQ_SAVE_VCF ? buildVcf() : buildCsv();
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            toast(contacts.size() + " contacts save ho gaye");
        } catch (Exception e) {
            toast("Save nahi hua: " + e.getMessage());
        }
    }

    private void copyAll() {
        if (contacts.isEmpty()) { toast("Koi contact nahi mila"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("contacts", buildCsv()));
        toast(contacts.size() + " contacts copy ho gaye (Naam, Number)");
    }

    private void share() {
        if (contacts.isEmpty()) { toast("Koi contact nahi mila"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, fileName(".vcf"));
        i.putExtra(Intent.EXTRA_TEXT, buildVcf());
        startActivity(Intent.createChooser(i, "Contacts bhejo"));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}

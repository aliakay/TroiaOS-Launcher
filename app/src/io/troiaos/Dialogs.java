package io.troiaos;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** The TV's own dialogs, for Customize and Set Up: a text field with the keyboard, and a message with one action. */
final class Dialogs {
    private Dialogs() {}

    static final int FIELD_WORDS = 0, FIELD_CODE = 1, FIELD_SECRET = 2;

    interface Answer { void text(String value); }

    /**
     * A text field in a dialog: the TV's keyboard comes up with it. `note` (optional) under the title. A secret is
     * typed as a visible password: keyboards don't learn or suggest it. `dismissed` runs when it closes (may be null).
     */
    static void ask(Activity a, String question, String note, String current, int kind, final Answer answer, final Runnable dismissed) {
        final EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setText(current);
        field.setSelection(field.getText().length());
        field.setInputType(kind == FIELD_SECRET
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : kind == FIELD_CODE ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (kind == FIELD_SECRET) field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setImeOptions(field.getImeOptions() | EditorInfo.IME_ACTION_DONE);
        int pad = Math.round(28 * a.getResources().getDisplayMetrics().widthPixels / 1920f);
        field.setPadding(pad, pad / 2, pad, pad / 2);
        final AlertDialog dialog = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(question)
                .setMessage(note)
                .setView(field)
                .setPositiveButton(Ui.tr("Kaydet", "Save"), (d, which) -> answer.text(field.getText().toString()))
                .setNegativeButton(Ui.tr("Vazgeç", "Cancel"), null)
                .create();
        field.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                answer.text(field.getText().toString());
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        if (dismissed != null) dialog.setOnDismissListener(d -> dismissed.run());
        dialog.show();
    }

    /** A message with one action and Cancel (or just OK when `action` is null). */
    static void message(Activity a, String title, String message, String action, final Runnable then, final Runnable dismissed) {
        choice(a, title, message, action, then, action != null ? Ui.tr("Vazgeç", "Cancel") : null, null, dismissed);
    }

    /** A message with two actions; Back closes it without either. A null `yes` shows just OK. */
    static void choice(Activity a, String title, String message, String yes, final Runnable onYes, String no, final Runnable onNo,
            final Runnable dismissed) {
        AlertDialog.Builder b = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message);
        if (yes != null) {
            b.setPositiveButton(yes, (d, w) -> { if (onYes != null) onYes.run(); });
            if (no != null) b.setNegativeButton(no, (d, w) -> { if (onNo != null) onNo.run(); });
        } else {
            b.setPositiveButton("OK", null);
        }
        AlertDialog d = b.create();
        if (dismissed != null) d.setOnDismissListener(x -> dismissed.run());
        d.show();
    }
}

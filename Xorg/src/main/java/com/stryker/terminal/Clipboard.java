package com.stryker.terminal;

import android.content.ClipboardManager;
import android.content.ClipboardManager.OnPrimaryClipChangedListener;
import android.content.Context;
import android.os.Build;
import com.stryker.terminal.bridge.StrykerLog;


public abstract class Clipboard {
  public static Clipboard get() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
      return NewerClipboard.Holder.Instance;
    return OlderClipboard.Holder.Instance;
  }

  public abstract void set(final Context context, final String text);

  public abstract String get(final Context context);

  public abstract void setListener(final Context context, final Runnable listener);

  private static class NewerClipboard extends Clipboard {
    private static class Holder {
      private static final NewerClipboard Instance = new NewerClipboard();
    }

    public void set(final Context context, final String text) {
      try {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(context.CLIPBOARD_SERVICE);
        if (clipboard != null)
          clipboard.setText(text);
      } catch (Exception e) {
        StrykerLog.i("SDL", "setClipboardText() exception: " + e.toString());
      }
    }

    public String get(final Context context) {
      String ret = "";
      try {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.getText() != null)
          ret = clipboard.getText().toString();
      } catch (Exception e) {
        StrykerLog.i("SDL", "getClipboardText() exception: " + e.toString());
      }
      return ret;
    }

    public void setListener(final Context context, final Runnable listener) {
      ClipboardManager clipboard = (ClipboardManager) context.getSystemService(context.CLIPBOARD_SERVICE);
      clipboard.addPrimaryClipChangedListener(new OnPrimaryClipChangedListener() {
        public void onPrimaryClipChanged() {
          listener.run();
        }
      });
    }
  }

  private static class OlderClipboard extends Clipboard {
    private static class Holder {
      private static final OlderClipboard Instance = new OlderClipboard();
    }

    public void set(final Context context, final String text) {
      try {
        android.text.ClipboardManager clipboard = (android.text.ClipboardManager) context.getSystemService(context.CLIPBOARD_SERVICE);
        if (clipboard != null)
          clipboard.setText(text);
      } catch (Exception e) {
        StrykerLog.i("SDL", "setClipboardText() exception: " + e.toString());
      }
    }

    public String get(final Context context) {
      String ret = "";
      try {
        android.text.ClipboardManager clipboard = (android.text.ClipboardManager) context.getSystemService(context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.getText() != null)
          ret = clipboard.getText().toString();
      } catch (Exception e) {
        StrykerLog.i("SDL", "getClipboardText() exception: " + e.toString());
      }
      return ret;
    }

    public void setListener(final Context context, final Runnable listener) {
      StrykerLog.i("SDL", "Cannot set clipboard listener on Android 2.3 or older");
    }
  }
}



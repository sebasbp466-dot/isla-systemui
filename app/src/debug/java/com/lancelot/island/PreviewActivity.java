package com.lancelot.island;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Solo para la vista previa automatica (GitHub Actions + emulador): pone un fondo de color y
 * arranca los mismos eventos que en SystemUI. La isla aparece como ventana superpuesta y se
 * maneja con "am broadcast -a com.lancelot.island.TEST --ei tipo N".
 */
public class PreviewActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E3C72, 0xFF2A5298, 0xFFB06AB3}));
        TextView t = new TextView(this);
        t.setText("Vista previa de la isla");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(22f);
        raiz.addView(t, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        setContentView(raiz);
        Context app = getApplicationContext();
        Eventos.iniciar(app);
        Registro.log("[IslaSystemUI] vista previa lista");
    }
}

package local.offerin.practice;

import android.content.Context;
import android.widget.TextView;
import io.noties.markwon.Markwon;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.ext.latex.JLatexMathPlugin;

public final class AnswerRenderer {
    private AnswerRenderer() {}
    public static Markwon create(Context context){
        float size=15*context.getResources().getDisplayMetrics().scaledDensity;
        return Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(size,builder->builder.inlinesEnabled(true)))
            .build();
    }
    public static void render(Markwon markdown,TextView view,String raw){
        try{markdown.setMarkdown(view,MathMarkdown.normalize(raw));}
        catch(RuntimeException error){view.setText(raw);}
    }
}

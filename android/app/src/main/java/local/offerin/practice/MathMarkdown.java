package local.offerin.practice;

/** Adapt common model math delimiters to Markwon's double-dollar syntax, outside code. */
public final class MathMarkdown {
    private MathMarkdown() {}
    public static String normalize(String source) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<source.length();) {
            char ch=source.charAt(i);
            if(ch=='`'||(ch=='~'&&lineStart(source,i))) {
                int run=1;while(i+run<source.length()&&source.charAt(i+run)==ch)run++;
                boolean fence=run>=3&&lineStart(source,i);
                if(ch=='`'||fence) {
                    String marker=source.substring(i,i+run);int end=i+run;
                    while((end=source.indexOf(marker,end))>=0) {
                        int after=end+run;
                        boolean exact=(after==source.length()||source.charAt(after)!=ch);
                        if(exact&&(!fence||(lineStart(source,end)&&lineEnd(source,after))))break;
                        end=after;
                    }
                    if(end<0){out.append(source.substring(i));break;}
                    out.append(source,i,end+run);i=end+run;continue;
                }
            }
            if(source.startsWith("\\$",i)||source.startsWith("\\\\",i)) {
                out.append(source,i,i+2);i+=2;continue;
            }
            String open=null,close=null;boolean block=false;
            if(source.startsWith("\\(",i)){open="\\(";close="\\)";}
            else if(source.startsWith("\\[",i)){open="\\[";close="\\]";block=true;}
            else if(ch=='$'){block=source.startsWith("$$",i);open=block?"$$":"$";close=open;}
            if(open!=null) {
                int start=i+open.length(),end=findClose(source,close,start);
                if(end>start) {
                    String math=source.substring(start,end);
                    boolean single="$".equals(open);
                    boolean valid=!math.trim().isEmpty()&&(!single||(!Character.isWhitespace(math.charAt(0))&&!Character.isWhitespace(math.charAt(math.length()-1))&&math.indexOf('\n')<0&&(end+1==source.length()||!Character.isDigit(source.charAt(end+1)))));
                    if(valid) {
                        if(block){if(out.length()>0&&out.charAt(out.length()-1)!='\n')out.append('\n');out.append("$$\n").append(math.trim()).append("\n$$\n");}
                        else out.append("$$").append(math.trim()).append("$$");
                        i=end+close.length();continue;
                    }
                }
            }
            String dots=null;
            for(String command:new String[]{"\\dots","\\ldots","\\cdots","\\vdots","\\ddots"}) {
                int end=i+command.length();
                if(source.startsWith(command,i)&&(end==source.length()||!Character.isLetter(source.charAt(end)))){dots=command;break;}
            }
            if(dots!=null){out.append(dots.equals("\\cdots")?'⋯':dots.equals("\\vdots")?'⋮':dots.equals("\\ddots")?'⋱':'…');i+=dots.length();}
            else {out.append(ch);i++;}
        }
        return out.toString();
    }
    private static int findClose(String text,String close,int start){
        for(int p=start;(p=text.indexOf(close,p))>=0;p+=close.length()) {
            int slashes=0;for(int j=p-1;j>=0&&text.charAt(j)=='\\';j--)slashes++;
            if(slashes%2==0)return p;
        }
        return -1;
    }
    private static boolean lineStart(String text,int pos){int start=text.lastIndexOf('\n',pos-1)+1;if(pos-start>3)return false;for(int i=start;i<pos;i++)if(text.charAt(i)!=' ')return false;return true;}
    private static boolean lineEnd(String text,int pos){for(int i=pos;i<text.length()&&text.charAt(i)!='\n';i++)if(text.charAt(i)!=' '&&text.charAt(i)!='\r')return false;return true;}
}

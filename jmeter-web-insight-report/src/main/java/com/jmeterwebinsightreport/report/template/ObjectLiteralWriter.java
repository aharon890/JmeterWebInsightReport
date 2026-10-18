package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.util.AtomicFileWriter;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * JSON text in, the same text out as a JavaScript object literal that means what the JSON means.
 *
 * <p>The external-data sidecar is {@code window.__WIR_DATA__={...};}, loaded with
 * {@code <script src>} and so evaluated as JavaScript, not parsed as JSON. The two agree on every
 * key but one: in an object literal {@code "__proto__": v} sets the object's prototype instead of
 * adding an own key (JSON.parse, the inline payload's reader, keeps it as a key). A component
 * named {@code __proto__} therefore lost its entry on exactly the reports big enough for a
 * sidecar: its asserts named an undeclared component, and a Download wrote them without it, which
 * the next run refuses (N2).
 *
 * <p>So a {@code __proto__} KEY is written in the computed form {@code ["__proto__"]:}, which is an
 * own key with the same value. Everything else passes through untouched, byte for byte: a string
 * value that reads {@code "__proto__"}, a key that merely contains it, the text inside any string.
 * The writer tracks whether it is inside a string (escapes included) and holds back only a string
 * short enough to be the key, until the character after it says whether it was one. A key is
 * compared by its value after its escapes, as the literal reads it: a key that spells an
 * underscore as a unicode escape is {@code __proto__} too, although Jackson never writes one.
 *
 * <p>Both of the generator's paths to the sidecar go through {@link #assignment}, the one place
 * the wrapper and this rewrite are written: the threshold path (a payload already serialised) and
 * the forced path (streamed straight from Jackson).
 *
 * <p>Call {@link #finish()} at the end, never {@link #close()} on the target's behalf: a held
 * string is written by finish, and {@link #flush()} deliberately leaves it held, because a flush
 * mid-stream must not decide a key before its colon arrives.
 */
public final class ObjectLiteralWriter extends FilterWriter {

    private static final String PROTO = "__proto__";

    /**
     * The longest string worth holding back: {@code "__proto__"} with every character a six-character
     * escape, quotes included, is 56. A longer string cannot be the key and passes straight through.
     */
    private static final int HOLD_LIMIT = 2 + 6 * PROTO.length();

    private boolean inString;
    private boolean escaped;
    /** A string held back from its opening quote, while it could still be the key. */
    private final StringBuilder held = new StringBuilder(HOLD_LIMIT + 1);
    private boolean holding;
    /** A complete {@code "__proto__"} string, and any whitespace after it, awaiting the next character. */
    private String candidate;
    private final StringBuilder candidateSpace = new StringBuilder();

    private final char[] chunk = new char[8192];

    public ObjectLiteralWriter(Writer out) {
        super(out);
    }

    /**
     * {@code prefix}, then the JSON {@code body} writes, as an object literal, then {@code ;} — the
     * sidecar. The one writer both paths use, so neither can write the wrapper without the rewrite.
     *
     * @param body writes the payload's JSON text to the writer it is handed, and does not close it
     */
    public static AtomicFileWriter.ContentWriter assignment(String prefix,
                                                            AtomicFileWriter.ContentWriter body) {
        return writer -> {
            writer.write(prefix);
            ObjectLiteralWriter literal = new ObjectLiteralWriter(writer);
            body.writeTo(literal);
            literal.finish();
            writer.write(";");
        };
    }

    @Override
    public void write(int c) throws IOException {
        chunk[0] = (char) c;
        write(chunk, 0, 1);
    }

    @Override
    public void write(String s, int off, int len) throws IOException {
        // In pieces: the threshold path hands over the whole payload as one String, and
        // toCharArray would copy all of it at once.
        for (int at = off, end = off + len; at < end; ) {
            int n = Math.min(end - at, chunk.length);
            s.getChars(at, at + n, chunk, 0);
            write(chunk, 0, n);
            at += n;
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        int end = off + len;
        int run = off;   // start of the characters passing straight through
        for (int i = off; i < end; i++) {
            char c = cbuf[i];
            if (candidate != null) {
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    candidateSpace.append(c);
                    run = i + 1;
                    continue;
                }
                writeCandidate(c == ':');
                run = i;
            }
            if (holding) {
                held.append(c);
                run = i + 1;
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                    holding = false;
                    release();
                    continue;
                }
                if (held.length() > HOLD_LIMIT) {
                    out.append(held);
                    held.setLength(0);
                    holding = false;   // still inside the string, now passing through
                }
                continue;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                out.write(cbuf, run, i - run);
                inString = true;
                escaped = false;
                holding = true;
                held.setLength(0);
                held.append(c);
                run = i + 1;
            }
        }
        if (run < end) {
            out.write(cbuf, run, end - run);
        }
    }

    /** Write whatever is still held — the end of the text decides nothing more — and flush. */
    public void finish() throws IOException {
        if (candidate != null) {
            writeCandidate(false);
        }
        if (holding) {
            out.append(held);
            held.setLength(0);
            holding = false;
        }
        out.flush();
    }

    /** Flushes the target only; a held string stays held. See the class comment. */
    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        finish();
        super.close();
    }

    /** A held string has closed: the key's spelling waits for the next character, any other goes out. */
    private void release() throws IOException {
        if (PROTO.equals(decode(held))) {
            candidate = held.toString();
            candidateSpace.setLength(0);
        } else {
            out.append(held);
        }
        held.setLength(0);
    }

    private void writeCandidate(boolean isKey) throws IOException {
        if (isKey) {
            out.write('[');
            out.write(candidate);
            out.write(']');
        } else {
            out.write(candidate);
        }
        out.append(candidateSpace);
        candidate = null;
        candidateSpace.setLength(0);
    }

    /** A JSON string token's value, or null when it is not one this decoder can read. */
    private static String decode(CharSequence token) {
        StringBuilder value = new StringBuilder(PROTO.length());
        int last = token.length() - 1;
        for (int i = 1; i < last; i++) {
            char c = token.charAt(i);
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (++i >= last) {
                return null;
            }
            char e = token.charAt(i);
            switch (e) {
                case '"': case '\\': case '/': value.append(e); break;
                case 'b': value.append('\b'); break;
                case 'f': value.append('\f'); break;
                case 'n': value.append('\n'); break;
                case 'r': value.append('\r'); break;
                case 't': value.append('\t'); break;
                case 'u':
                    if (i + 4 >= last) {
                        return null;
                    }
                    try {
                        value.append((char) Integer.parseInt(token.subSequence(i + 1, i + 5).toString(), 16));
                    } catch (NumberFormatException bad) {
                        return null;
                    }
                    i += 4;
                    break;
                default:
                    return null;
            }
        }
        return value.toString();
    }
}

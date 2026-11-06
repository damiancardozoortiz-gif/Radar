package com.radar.jarvis.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Las notas de JARVIS convertidas en algo que se puede buscar (versión Java de agent/vault.py). Solo lectura. */
public final class Vault {
    public interface Source {
        String read(String path) throws java.io.IOException;
        String[] list(String dir) throws java.io.IOException;
    }

    public static final class Note {
        public final String id, title, type, body;
        final Set<String> tokens, titleTokens;
        Note(String id, String title, String type, String body) {
            this.id = id; this.title = title; this.type = type; this.body = body;
            this.tokens = new HashSet<String>(tokens(title + " " + body));
            this.titleTokens = new HashSet<String>(tokens(title));
        }
    }

    static final Set<String> STOP = new HashSet<String>(Arrays.asList((
        "a al algo algun alguna algunas alguno algunos ante antes asi aun bien cada como con contra cual cuales cuando cuanto " +
        "cuanta cuantos cuantas de del desde donde dos el ella ellas ellos en entre era eres es esa esas ese eso esos esta estan " +
        "estas este esto estos estoy fue ha han has hay he la las le les lo los mas me mi mis mucho muy nada ni no nos nosotros o " +
        "os otra otro para pero poco por porque puede que quien quienes se sea ser si sin sobre solo son su sus tambien te tengo " +
        "ti tiene tienen todo todos tu tus un una unas uno unos usted ustedes va vamos y ya yo dime dame decir sabes saber quiero " +
        "hola oye jarvis buenas buenos dias favor gracias puedes podrias hacer tal ver mira cosa cosas mio mia").split(" ")));
    private static final Pattern FRONT = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    public final List<Note> notes = new ArrayList<Note>();
    public final Map<String, Note> byTitle = new LinkedHashMap<String, Note>();
    public final List<String> skipped = new ArrayList<String>();

    public static String norm(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static String stem(String t) {
        if (t.length() > 5 && t.endsWith("ones")) return t.substring(0, t.length() - 2);
        if (t.length() > 4 && t.endsWith("s") && !t.endsWith("ss")) return t.substring(0, t.length() - 1);
        return t;
    }

    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<String>();
        Matcher m = WORD.matcher(norm(s));
        while (m.find()) {
            String t = m.group();
            if (t.length() > 1 && !STOP.contains(t)) out.add(stem(t));
        }
        return out;
    }

    public Vault load(Source src, String root) {
        try {  // paquete de notas (nombres con tildes intactos): [{"path": "vault/...", "text": "..."}]
            List<Object> all = Json.arr(Json.parse(src.read(root + ".json")));
            for (Object o : all) {
                Map<String, Object> f = Json.obj(o);
                String path = Json.str(f.get("path"));
                if (path.endsWith(".pdf")) skipped.add(path + " (PDF: aún no se lee en la app)");
                else add(path, Json.str(f.get("text")));
            }
            return this;
        } catch (java.io.IOException e) {
            walk(src, root);  // sin paquete: carpeta normal
            return this;
        }
    }

    private void add(String path, String text) {
        String name = path.substring(path.lastIndexOf('/') + 1), dir = path.substring(0, Math.max(0, path.lastIndexOf('/')));
        String type = null, body = text;
        Matcher fm = FRONT.matcher(text);
        if (fm.find()) {
            for (String line : fm.group(1).split("\n")) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("tipo")) type = line.substring(c + 1).trim().toLowerCase();
            }
            body = text.substring(fm.end());
        }
        String title = name.substring(0, name.lastIndexOf('.'));
        if (type == null) type = norm(dir.substring(dir.lastIndexOf('/') + 1));
        Note n = new Note(path, title, type, body);
        notes.add(n);
        if (!byTitle.containsKey(norm(title))) byTitle.put(norm(title), n);
    }

    private void walk(Source src, String dir) {
        String[] items;
        try { items = src.list(dir); } catch (java.io.IOException e) { skipped.add(dir + " (no se pudo leer)"); return; }
        Arrays.sort(items);
        for (String name : items) {
            String path = dir + "/" + name;
            if (name.endsWith(".md") || name.endsWith(".txt")) {
                try {
                    add(path, src.read(path));
                } catch (java.io.IOException e) {
                    skipped.add(path + " (no se pudo leer)");
                }
            } else if (name.endsWith(".pdf")) {
                skipped.add(path + " (PDF: aún no se lee en la app)");
            } else if (!name.contains(".")) {
                walk(src, path);  // subcarpeta
            }
        }
    }

    public static final class Hit {
        public final double score; public final Note note;
        Hit(double s, Note n) { score = s; note = n; }
    }

    /** Busca por palabras con significado (sin "que", "de"...). */
    public List<Hit> search(String query, int k) {
        List<String> q = tokens(query);
        List<Hit> res = new ArrayList<Hit>();
        if (q.isEmpty()) return res;
        String qn = norm(query);
        for (Note n : notes) {
            double sc = 0;
            for (String t : q) sc += n.titleTokens.contains(t) ? 3 : n.tokens.contains(t) ? 1 : 0;
            if (n.title.length() > 3 && qn.contains(norm(n.title))) sc += 6;
            if (sc > 0) res.add(new Hit(sc, n));
        }
        Collections.sort(res, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) { return Double.compare(b.score, a.score); }
        });
        return res.size() > k ? res.subList(0, k) : res;
    }

    /** Lo atrasado y lo que vence: líneas de las pólizas con impagos o vencimientos (con su archivo). */
    public List<String> overdue(String today, int max) {
        List<String> out = new ArrayList<String>();
        Pattern imp = Pattern.compile("impagad\\w* desde hace (\\d+) d");
        Pattern prima = Pattern.compile("Prima anual:\\s*([\\d.,]+)");
        Pattern cli = Pattern.compile("Cliente:\\s*\\[\\[([^\\]]+)\\]\\]");
        Pattern venc = Pattern.compile("Vencimiento:\\s*(\\d{4}-\\d{2}-\\d{2})");
        for (Note n : notes) {
            if (!"poliza".equals(n.type)) continue;
            Matcher mi = imp.matcher(n.body), mp = prima.matcher(n.body), mc = cli.matcher(n.body), mv = venc.matcher(n.body);
            String c = mc.find() ? mc.group(1) : "?";
            String p = mp.find() ? mp.group(1) : "?";
            if (mi.find()) out.add(c + ": recibo impagado " + n.title + " desde hace " + mi.group(1) + " días · prima " + p + " € · archivo Polizas/" + n.title + ".md");
            else if (mv.find() && mv.group(1).compareTo(today) < 0) out.add(c + ": " + n.title + " vencida el " + mv.group(1) + " sin renovar · prima " + p + " € · archivo Polizas/" + n.title + ".md");
        }
        if (out.size() > max) return new ArrayList<String>(out.subList(0, max));
        return out;
    }
}

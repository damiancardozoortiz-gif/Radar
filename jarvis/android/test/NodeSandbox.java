import com.radar.jarvis.core.Lab;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Aislamiento para las pruebas en el ordenador: contexto vm de Node vacío, con límite de tiempo (en el teléfono: WebView sin red). */
public class NodeSandbox implements Lab.Sandbox {
    static final String RUNNER = "const vm=require('vm');let d='';process.stdin.setEncoding('utf8');process.stdin.on('data',c=>d+=c).on('end',()=>{"
            + "try{const r=vm.runInContext(d,vm.createContext(Object.create(null)),{timeout:+process.argv[1]});process.stdout.write(String(r));}"
            + "catch(e){process.stderr.write(String(e&&e.message||e));process.exit(2);}});";
    public String run(String js, long timeoutMs) throws Exception {
        Process p = new ProcessBuilder("node", "-e", RUNNER, String.valueOf(timeoutMs)).start();
        OutputStream o = p.getOutputStream(); o.write(js.getBytes("UTF-8")); o.close();
        if (!p.waitFor(timeoutMs + 5000, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); throw new IOException("tiempo agotado"); }
        String out = new String(p.getInputStream().readAllBytes(), "UTF-8"), err = new String(p.getErrorStream().readAllBytes(), "UTF-8");
        if (p.exitValue() != 0) throw new IOException(err.contains("timed out") ? "tiempo agotado: la herramienta no terminó" : err);
        return out;
    }
}

package com.novelradar.quickjs;

import com.dokar.quickjs.QuickJs;
import com.dokar.quickjs.QuickJsException;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;

/** Standalone JNI regression: no app, Gradle, host bindings, files, or network in JavaScript. */
public final class NativeSecurityRegression {
    private static int passed;
    private static int failed;

    /** Deliberately not a Throwable; its only effect is an in-memory counter. */
    public static final class Sentinel {
        static final AtomicInteger constructions = new AtomicInteger();
        public Sentinel(String ignored) { constructions.incrementAndGet(); }
    }

    private static QuickJs create() throws Exception {
        QuickJs js = QuickJs.Companion.create(Dispatchers.getDefault());
        js.setEvaluationTimeoutMillis(300);
        js.setMemoryLimit(16L * 1024 * 1024);
        js.setMaxStackSize(512L * 1024);
        return js;
    }

    private static Object eval(QuickJs js, String code) throws Exception {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
            (scope, continuation) -> js.evaluateInternal(code, "native-regression.js", false, continuation));
    }

    private static String errorExpression() {
        return "Object.assign(new Error('sentinel 中文😀'), {name:'" + Sentinel.class.getName() + "'})";
    }

    private static void check(boolean condition, String label) {
        if (condition) { passed++; System.out.println("PASS " + label); }
        else { failed++; System.out.println("FAIL " + label); }
    }

    private static Throwable failure(QuickJs js, String code) {
        try { eval(js, code); return null; }
        catch (Throwable error) { return error; }
    }

    private static void sentinel(String label, String code, boolean legacy) throws Exception {
        Sentinel.constructions.set(0);
        Object result = null;
        Throwable error = null;
        try (QuickJs js = create()) {
            try { result = eval(js, code); } catch (Throwable caught) { error = caught; }
        }
        int count = Sentinel.constructions.get();
        System.out.println("OBSERVE " + label + " constructors=" + count + " result=" +
            (result == null ? "null" : result.getClass().getName()) + " error=" +
            (error == null ? "null" : error.getClass().getName()));
        check(legacy ? count > 0 : count == 0, label + (legacy ? " exposes old constructor path" : " does not construct Java class"));
        if (!legacy) {
            Object mapped = error != null ? error : result;
            if (label.equals("handled-promise") || label.equals("sync-catch")) {
                check("ok".equals(result) && error == null, label + " completes normally");
            } else {
                check(mapped != null && mapped.getClass() == QuickJsException.class,
                    label + " maps to fixed QuickJsException");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        boolean legacy = args.length == 1 && args[0].equals("--expect-legacy");
        System.out.println("Native security regression mode=" + (legacy ? "legacy evidence" : "hardened"));
        sentinel("throw", "throw " + errorExpression() + ";", legacy);
        sentinel("returned-error", errorExpression(), legacy);
        sentinel("unhandled-promise", "Promise.reject(" + errorExpression() + "); 'ok';", legacy);
        sentinel("handled-promise", "Promise.reject(" + errorExpression() + ").catch(() => {}); 'ok';", legacy);
        if (!legacy) {
            sentinel("sync-catch", "try { throw " + errorExpression() + "; } catch(e) {} 'ok';", false);
            try (QuickJs js = create()) {
                check("文中😀\u0000尾".equals(eval(js, "'中文😀\\0尾'.replace('中文', '文中')")),
                    "Chinese, emoji, embedded NUL round-trip and transform");
                check("😀中文\u0000".equals(eval(js, "JSON.parse(JSON.stringify('😀中文\\0'))")),
                    "JSON Chinese, emoji, embedded NUL round-trip");
                Throwable error = failure(js, "throw new TypeError('错误😀\\0尾')");
                check(error != null && error.getClass() == QuickJsException.class &&
                        error.getMessage().contains("TypeError: 错误😀\u0000尾"),
                    "ordinary JS error preserves name, Unicode, NUL message");
                check(error instanceof QuickJsException && ((QuickJsException) error).getStack() != null &&
                        ((QuickJsException) error).getStack().contains("native-regression.js"),
                    "ordinary JS error preserves stack");
                check("undefined,undefined,undefined,undefined,undefined".equals(eval(js,
                    "[typeof std,typeof os,typeof fetch,typeof require,typeof Java].join(',')")),
                    "no default host I/O bindings");
                long start = System.nanoTime();
                Throwable interrupted = failure(js, "while(true) {}");
                long millis = (System.nanoTime() - start) / 1_000_000;
                System.out.println("OBSERVE timeoutMillis=" + millis + " exception=" + interrupted);
                check(interrupted != null && millis >= 150 && millis < 3000,
                    "300ms native deadline interrupts infinite loop");
                check("recovered".equals(eval(js, "'recovered'")), "runtime works after interrupt");
            }
            try (QuickJs js = create()) {
                js.setMemoryLimit(2L * 1024 * 1024);
                Throwable limited = failure(js, "const a=[]; for(let i=0;i<10000000;i++) a.push({value:i}); 'unexpected';");
                System.out.println("OBSERVE memoryLimit exception=" + limited);
                check(limited != null && limited.getClass() == QuickJsException.class,
                    "native memory limit rejects allocation");
            }
            QuickJs closed = create();
            closed.close();
            closed.close();
            check(closed.isClosed(), "close is idempotent");
            check(failure(closed, "'not evaluated'") != null, "evaluation after close fails");
        }
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed != 0) System.exit(1);
    }
}

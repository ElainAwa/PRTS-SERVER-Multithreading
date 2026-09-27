import org.objectweb.asm.*;
import java.nio.file.*;
/**
 * Toolchain invariant check for the PRTS base line:
 * every @Redirect annotation must carry its `at` element as a NESTED ANNOTATION
 * (sponge-mixin 0.17.2 and older), not as an ARRAY (sponge-mixin 0.17.4+).
 * Array form trips MixinExtras <= 0.5.4 with a ClassCastException at runtime.
 */
public class ScanAt {
    static int annotation = 0, list = 0;
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Files.walk(root).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
            try {
                new ClassReader(Files.readAllBytes(p)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                                if (!desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override public AnnotationVisitor visitAnnotation(String name, String d2) { if ("at".equals(name)) annotation++; return null; }
                                    @Override public AnnotationVisitor visitArray(String name) { if ("at".equals(name)) list++; return null; }
                                };
                            }
                        };
                    }
                }, 0);
            } catch (Throwable ignored) { }
        });
        System.out.println("ANNOTATION=" + annotation + " LIST=" + list);
        if (list > 0 || annotation == 0) {
            System.err.println("FAIL: @Redirect.at must compile to nested annotations (LIST must be 0, ANNOTATION > 0).");
            System.exit(1);
        }
    }
}
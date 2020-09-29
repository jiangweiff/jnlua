package org.terasology.jnlua;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Helper class to load JNI resources.
 *
 */
public final class NativeLibraryLoader {

    private static final String NATIVE_RESOURCE_HOME = "META-INF/native/";
    private static final File WORKDIR;

    public static String getSystemProperty(final String key, String def) {

        String value = null;
        try {
            if (System.getSecurityManager() == null) {
                value = System.getProperty(key);
            } else {
                value = AccessController.doPrivileged(new PrivilegedAction<String>() {
                    @Override
                    public String run() {
                        return System.getProperty(key);
                    }
                });
            }
        } catch (SecurityException e) {
        	System.out.format("Unable to retrieve a system property '{}'; default values will be used.", key, e);
        }
        if (value == null) {
            return def;
        }
        return value;
    }
    
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static File toDirectory(String path) {
        if (path == null) {
            return null;
        }

        File f = new File(path);
        f.mkdirs();

        if (!f.isDirectory()) {
            return null;
        }

        try {
            return f.getAbsoluteFile();
        } catch (Exception ignored) {
            return f;
        }
    }
    
    private static boolean isWindows() {
        boolean windows = getSystemProperty("os.name", "").toLowerCase(Locale.US).contains("win");
        return windows;
    }
    
    private static File tmpDir() {
        File f = null;
        try {
            f = toDirectory(getSystemProperty("java.io.tmpdir", null));
            if (f != null) {
            	System.out.format("-Dio.netty.tmpdir: {} (java.io.tmpdir)", f);
                return f;
            }

            // This shouldn't happen, but just in case ..
            if (isWindows()) {
                f = toDirectory(System.getenv("TEMP"));
                if (f != null) {
                	System.out.format("-Dio.netty.tmpdir: {} (%TEMP%)", f);
                    return f;
                }

                String userprofile = System.getenv("USERPROFILE");
                if (userprofile != null) {
                    f = toDirectory(userprofile + "\\AppData\\Local\\Temp");
                    if (f != null) {
                    	System.out.format("-Dio.netty.tmpdir: {} (%USERPROFILE%\\AppData\\Local\\Temp)", f);
                        return f;
                    }

                    f = toDirectory(userprofile + "\\Local Settings\\Temp");
                    if (f != null) {
                    	System.out.format("-Dio.netty.tmpdir: {} (%USERPROFILE%\\Local Settings\\Temp)", f);
                        return f;
                    }
                }
            } else {
                f = toDirectory(System.getenv("TMPDIR"));
                if (f != null) {
                	System.out.format("-Dio.netty.tmpdir: {} ($TMPDIR)", f);
                    return f;
                }
            }
        } catch (Throwable ignored) {
            // Environment variable inaccessible
        }
		return f;
    }
    
    static {
        WORKDIR = tmpDir();
    }

    /**
     * Load the given library with the specified {@link ClassLoader}
     */
    public static void load(String originalName, ClassLoader loader) {
        // Adjust expected name to support shading of native libraries.
        String name = originalName;
        System.out.print("loading lua native lib "+name);
        List<Throwable> suppressed = new ArrayList<Throwable>();
        try {
            // first try to load from java.library.path
        	System.loadLibrary(name);
            return;
        } catch (Throwable ex) {
            suppressed.add(ex);
        }

        String libname = System.mapLibraryName(name);
        String path = NATIVE_RESOURCE_HOME + libname;

        InputStream in = null;
        OutputStream out = null;
        File tmpFile = null;
        URL url;
        if (loader == null) {
            url = ClassLoader.getSystemResource(path);
        } else {
            url = loader.getResource(path);
        }
        try {
            if (url == null) {
                FileNotFoundException fnf = new FileNotFoundException(path);
                throw fnf;
            }

            int index = libname.lastIndexOf('.');
            String prefix = libname.substring(0, index);
            String suffix = libname.substring(index);

            tmpFile = File.createTempFile(prefix, suffix, WORKDIR);
            System.out.print("loading lua native lib from "+tmpFile.getPath());
            in = url.openStream();
            out = new FileOutputStream(tmpFile);

            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }

            out.flush();

            // Close the output stream before loading the unpacked library,
            // because otherwise Windows will refuse to load it when it's in use by other process.
            closeQuietly(out);
            out = null;
            System.load(tmpFile.getPath());
        } catch (UnsatisfiedLinkError e) {
            throw e;
        } catch (Exception e) {
            UnsatisfiedLinkError ule = new UnsatisfiedLinkError("could not load a native library: " + name);
            ule.initCause(e);
            throw ule;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            // After we load the library it is safe to delete the file.
            // We delete the file immediately to free up resources as soon as possible,
            // and if this fails fallback to deleting on JVM exit.
            if (tmpFile != null && !tmpFile.delete()) {
                tmpFile.deleteOnExit();
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
                // ignore
            }
        }
    }

    private NativeLibraryLoader() {
        // Utility
    }


}

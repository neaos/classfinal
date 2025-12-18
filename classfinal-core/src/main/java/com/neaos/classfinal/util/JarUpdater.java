package com.neaos.classfinal.util;

import java.io.*;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.*;
import java.util.zip.CRC32;

public class JarUpdater {

    /**
     * 替换 jar 中的单个 class 条目（不解压全包）
     * 对于原始 STORED 且缺少 size/crc 的条目，会先缓存到临时文件计算后再写入，避免 ZipOutputStream 抛错。
     *
     * @param jarPath        要修改的 jar 路径
     * //@param entryToReplace 要替换的条目名，例如 "com/example/Demo.class"
     * //@param newBytes       新 class 的字节
     */
    public static void replaceEntry(Path jarPath, Map<String,byte[]> newClassMap) throws IOException {
        //String entryToReplace, byte[] newBytes
        Path tmp = Files.createTempFile("jar-update-", ".jar");
        try (ZipFile zipFile = new ZipFile(jarPath.toFile());
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {

            Enumeration<? extends ZipEntry> en = zipFile.entries();

            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();

                boolean isTarget = newClassMap.containsKey(name);
                //boolean isTarget = name.equals(entryToReplace);
                Log.debug("name: " + name + ",isTarget=" + isTarget);

                if (isTarget) {
                    // 写入替换条目：使用 DEFLATED（因为我们没有预先计算 newBytes 的 CRC/size）
                    ZipEntry out = new ZipEntry(name);
                    out.setTime(e.getTime());
                    out.setComment(e.getComment());
                    out.setExtra(e.getExtra());
                    out.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(out);
                    zos.write(newClassMap.get(name));
                    zos.closeEntry();
                    continue;
                }

                // 非目标条目：尽量保留 STORED 的属性
                ZipEntry out = new ZipEntry(name);
                out.setTime(e.getTime());
                out.setComment(e.getComment());
                out.setExtra(e.getExtra());
                out.setMethod(e.getMethod());

                if (e.getMethod() == ZipEntry.STORED) {
                    long size = e.getSize();
                    long crc = e.getCrc();

                    if (size >= 0 && crc >= 0) {
                        // 元数据完备，直接设置并拷贝
                        out.setSize(size);
                        out.setCompressedSize(e.getCompressedSize());
                        out.setCrc(crc);
                        zos.putNextEntry(out);
                        try (InputStream is = zipFile.getInputStream(e)) {
                            copy(is, zos);
                        }
                        zos.closeEntry();
                    } else {
                        // 缺少 size 或 crc —— 先把内容写到临时文件，计算 size/crc 后再写回（避免 ZipOutputStream 抛错）
                        Path cache = Files.createTempFile("zip-entry-cache-", ".bin");
                        try (InputStream is = zipFile.getInputStream(e);
                             OutputStream os = Files.newOutputStream(cache, StandardOpenOption.TRUNCATE_EXISTING)) {
                            copy(is, os);
                        }

                        long computedSize = Files.size(cache);
                        long computedCrc = computeCrc(cache);

                        out.setSize(computedSize);
                        out.setCompressedSize(computedSize); // STORED 时 compressedSize == size
                        out.setCrc(computedCrc);

                        zos.putNextEntry(out);
                        try (InputStream is2 = Files.newInputStream(cache)) {
                            copy(is2, zos);
                        }
                        zos.closeEntry();

                        Files.deleteIfExists(cache);
                    }
                } else {
                    // DEFLATED：直接按流读取并写入（注意：这会导致重新压缩 -> 改变 compressedSize/CRC）
                    zos.putNextEntry(out);
                    try (InputStream is = zipFile.getInputStream(e)) {
                        copy(is, zos);
                    }
                    zos.closeEntry();
                }
            }
        }

        // 原地替换
        Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
    }

    private static long computeCrc(Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) crc.update(buf, 0, r);
        }
        return crc.getValue();
    }

    // 测试用例
//    public static void main(String[] args) throws Exception {
//        Path jar = Paths.get("example.jar");
//        byte[] newClass = Files.readAllBytes(Paths.get("com/example/Demo.class"));
//        replaceEntry(jar, "com/example/Demo.class", newClass);
//        System.out.println("done");
//    }
}


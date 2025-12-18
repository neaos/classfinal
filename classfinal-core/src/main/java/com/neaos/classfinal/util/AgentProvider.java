package com.neaos.classfinal.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class AgentProvider {
    static {
        String osName = System.getProperty("os.name").toLowerCase();
        String libName;

        if (osName.contains("win")) {
            libName = "agentprovider.dll";
        } else if (osName.contains("linux")) {
            libName = "libagentprovider.so";
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + osName);
        }
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"), libName);
            if (tmp.exists()) {
                try {
                    tmp.delete();
                } catch (Exception ignored) {
                }
            }
            if (!tmp.exists() || tmp.length() == 0) {
                try (InputStream in = AgentProvider.class.getResourceAsStream("/" + libName)) {
                    if (in == null) {
                        throw new RuntimeException(libName + " not found!");
                    }
                    Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.load(tmp.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + libName, e);
        }
    }

    /**
     * 调用 native so 加密 class 文件
     * @param srcClassFile 源 class 文件路径
     * @param outFile      输出加密文件路径
     * @param password     加密密码
     * @return 0 成功, 非0失败
     */
    public static native int encryptFile(String srcClassFile, String outFile, String password);
    public static native int decryptFile(String src, String out, String password);
}

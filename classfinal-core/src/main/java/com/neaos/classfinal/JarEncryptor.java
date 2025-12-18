package com.neaos.classfinal;

import javassist.ClassPool;
import javassist.NotFoundException;
import com.neaos.classfinal.util.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * java class加密
 *
 * @author neaos
 */
public class JarEncryptor {
    //加密配置文件：加载配置文件是注入解密代码的配置
    static Map<String, String> aopMap = new HashMap<>();

    static {
        //org.springframework.core.io.ClassPathResource#getInputStream注入解密功能
        aopMap.put("spring.class", "org.springframework.core.io.ClassPathResource#getInputStream");
        aopMap.put("spring.code", "char[] c=${passchar};"
                + "is=com.neaos.classfinal.JarDecryptor.getInstance().decryptConfigFile(this.path,is,c);");
        aopMap.put("spring.line", "999");

        //com.jfinal.kit.Prop#getInputStream注入解密功能
        aopMap.put("jfinal.class", "com.jfinal.kit.Prop#<Prop>(java.lang.String,java.lang.String)");
        aopMap.put("jfinal.code", "char[] c=${passchar};inputStream=com.neaos.classfinal.JarDecryptor.getInstance().decryptConfigFile(fileName,inputStream,c);");
        aopMap.put("jfinal.line", "62");
    }

    //要加密的jar或war
    private String jarPath = null;
    //要加密的包，多个用逗号隔开
    private List<String> packages = null;
    //-INF/lib下要加密的jar
    private List<String> includeJars = null;
    //排除的类名
    private List<String> excludeClass = null;
    //依赖jar路径
    private List<String> classPath = null;
    //需要加密的配置文件
    private List<String> cfgfiles = null;
    //密码
    private char[] password = null;

    //jar还是war
    private String jarOrWar = null;
    //工作目录
    private File targetDir = null;
    //-INF/lib目录
    private File targetLibDir = null;
    //-INF/classes目录
    private File targetClassesDir = null;
    //加密的文件数量
    private Integer encryptFileCount = null;
    //存储解析出来的类名和路径
    private Map<String, String> resolveClassName = new HashMap<>();

    /**
     * 构造方法
     *
     * @param jarPath  要加密的jar或war
     * @param password 密码
     */
    public JarEncryptor(String jarPath, char[] password) {
        super();
        this.jarPath = jarPath;
        this.password = password;
    }

    /**
     * 加密jar的主要过程
     *
     * @return 解密后生成的文件的绝对路径
     */
    public String doEncryptJar() {
        Log.debug("jarPath：" + jarPath);
        if (!jarPath.endsWith(".jar") && !jarPath.endsWith(".war")) {
            throw new RuntimeException("jar/war文件格式有误");
        }
        if (!new File(jarPath).exists()) {
            throw new RuntimeException("文件不存在:" + jarPath);
        }
        if (password == null || password.length == 0) {
            throw new RuntimeException("密码不能为空");
        }

        this.jarOrWar = jarPath.substring(jarPath.lastIndexOf(".") + 1);
        Log.debug("加密类型：" + jarOrWar);
        //临时work目录
        this.targetDir = new File(jarPath.replace("." + jarOrWar, Const.LIB_JAR_DIR));
        this.targetLibDir = new File(this.targetDir, ("jar".equals(jarOrWar) ? "BOOT-INF" : "WEB-INF")
                + File.separator + "lib");
        this.targetClassesDir = new File(this.targetDir, ("jar".equals(jarOrWar) ? "BOOT-INF" : "WEB-INF")
                + File.separator + "classes");
        Log.debug("临时目录：" + targetDir);

        String pw = String.valueOf(password) + "+" + String.join(",", packages);
        Log.debug("pw：" + pw);
        String targetJar = jarPath.replace("." + jarOrWar, "-encrypted." + jarOrWar);
        Log.debug("targetJar：" + targetJar);
        int ret = AgentProvider.encryptFile(new File(jarPath).getAbsolutePath(), new File(targetJar).getAbsolutePath(), pw);
        Log.debug("ret：" + ret);
        if (ret != 0) {
            throw new RuntimeException("Native encryption failed for " + jarPath);
        }
        Log.debug("start to clear");

        //[1]释放所有文件
        List<String> allFile = JarUtils.unJar(jarPath, this.targetDir.getAbsolutePath());
        allFile.forEach(s -> Log.debug("释放：" + s));
        //[1.1]内部jar只释放需要加密的jar
        List<String> libJarFiles = new ArrayList<>();
        allFile.forEach(path -> {
            if (!path.toLowerCase().endsWith(".jar")) {
                return;
            }
            String name = path.substring(path.lastIndexOf(File.separator) + 1);
            if (StrUtils.isMatchs(this.includeJars, name, false)) {
                String targetPath = path.substring(0, path.length() - 4) + Const.LIB_JAR_DIR;
                List<String> files = JarUtils.unJar(path, targetPath);
                files.forEach(s -> Log.debug("释放：" + s));
                libJarFiles.add(path);
                libJarFiles.addAll(files);
            }
        });
        allFile.addAll(libJarFiles);
        allFile.forEach(s -> Log.debug("filterClass：" + s));

        //[2]提取所有需要加密的class文件
        List<File> classFiles = filterClasses(allFile);

        //[5]清空class方法体，并保存文件
        classFiles.forEach(s -> Log.debug("clearClass：" + s.getName()));
        clearClassMethod(classFiles);
        Log.debug("end to clearClassMethod");

        String result = packJar(classFiles,targetJar);
        Log.debug("end to packageJar");

        IoUtils.delete(this.targetDir);

        //[6]加密配置文件
        //encryptConfigFile();

        //删除META-INF下的maven
        //IoUtils.delete(new File(this.targetDir, "META-INF/maven"));

        return result;
    }

    public String packJar(List<File> classFiles, String targetJar) {
        try {
            Map<String,byte[]> newClassMap = new HashMap<>();
            for (File classFile : classFiles) {
                String className = classFile.getName();
                if (className.endsWith(".class")) {
                    className = resolveClassName(classFile.getAbsolutePath(), true);
                }
                String targetEntry = "BOOT-INF/classes/" + className.replace('.', '/') + ".class";
                byte[] newClass = Files.readAllBytes(classFile.toPath());
                Log.debug("targetEntry: " + targetEntry);
                newClassMap.put(targetEntry,newClass);
            }
            Path jar = Paths.get(targetJar);
            JarUpdater.replaceEntry(jar,newClassMap);
        } catch (Exception e) {
            e.printStackTrace();
            Log.println("打包失败: " + e.getMessage());
            return null;
        }
        return targetJar;
    }


    /**
     * 找出所有需要加密的class文件
     *
     * @param allFile 所有文件
     * @return 待加密的class列表
     */
    public List<File> filterClasses(List<String> allFile) {
        List<File> classFiles = new ArrayList<>();
        allFile.forEach(file -> {
            if (!file.endsWith(".class")) {
                return;
            }
            //解析出类全名
            String className = resolveClassName(file, true);
            //判断包名相同和是否排除的类
            if (StrUtils.isMatchs(this.packages, className, false)
                    && !StrUtils.isMatchs(this.excludeClass, className, false)) {
                classFiles.add(new File(file));
                Log.debug("待加密: " + file);
            }
        });
        return classFiles;
    }

    private List<File> copyClassFiles(List<File> classFiles) {
        List<File> pendingClassFiles = new ArrayList<>();
        //加密后存储的位置
        File metaDir = new File(this.targetDir, "META-INF" + File.separator + Const.FILE_NAME);
        if (!metaDir.exists()) {
            metaDir.mkdirs();
        }
        for (File classFile : classFiles) {
            String className = classFile.getName();
            try {
                File newClassFile = new File(metaDir, className);
                Files.copy(classFile.toPath(), newClassFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                pendingClassFiles.add(newClassFile);
            } catch (IOException e) {
                throw new RuntimeException("Class file copy failed for " + className);
            }
        }
        return pendingClassFiles;
    }

    /**
     * 加密class文件，放在META-INF/classes里
     *
     * @param classFiles jar/war 下需要加密的class文件
     * @return 已经加密的类名
     */
    private List<String> encryptClass(List<File> classFiles) {
        List<String> encryptClasses = new ArrayList<>();

        //加密后存储的位置
        File metaDir = new File(this.targetDir, "META-INF" + File.separator + Const.FILE_NAME);
        if (!metaDir.exists()) {
            metaDir.mkdirs();
        }

        //加密另存
        classFiles.forEach(classFile -> {
            String className = classFile.getName();
            if (className.endsWith(".class")) {
                className = resolveClassName(classFile.getAbsolutePath(), true);
            }
            File targetFile = new File(metaDir, className + ".enc");
            String pw = new String(password);
            int ret = AgentProvider.encryptFile(classFile.getAbsolutePath(), targetFile.getAbsolutePath(), pw);
            if (ret != 0) {
                Log.debug("加密失败：" + className);
                throw new RuntimeException("Native encryption failed for " + className);
            }

            encryptClasses.add(className);
            Log.debug("加密：" + className + " -> " + targetFile.getAbsolutePath());
        });

        //加密密码hash存储，用来验证密码是否正确
        char[] pchar = EncryptUtils.md5(StrUtils.merger(this.password, EncryptUtils.SALT));
        pchar = EncryptUtils.md5(StrUtils.merger(EncryptUtils.SALT, pchar));
        IoUtils.writeFile(new File(metaDir, Const.CONFIG_PASSHASH), StrUtils.toBytes(pchar));

        return encryptClasses;
    }

    /**
     * 清空class文件的方法体，并保留参数信息
     *
     * @param classFiles jar/war 下需要加密的class文件
     */
    private void clearClassMethod(List<File> classFiles) {
        //初始化javassist
        ClassPool pool = ClassPool.getDefault();
        //[1]把所有涉及到的类加入到ClassPool的classpath
        //[1.1]lib目录所有的jar加入classpath
        ClassUtils.loadClassPath(pool, this.targetLibDir);
        Log.debug("ClassPath: " + this.targetLibDir.getAbsolutePath());

        //[1.2]外部依赖的lib加入classpath
        ClassUtils.loadClassPath(pool, this.classPath);
        //this.classPath.forEach(classPath -> Log.debug("ClassPath: " + classPath));

        //[1.3]要修改的class所在的目录（-INF/classes 和 libjar）加入classpath
        List<String> classPaths = new ArrayList<>();
        classFiles.forEach(classFile -> {
            String classPath = resolveClassName(classFile.getAbsolutePath(), false);
            if (classPaths.contains(classPath)) {
                return;
            }
            try {
                pool.insertClassPath(classPath);
            } catch (NotFoundException e) {
                //Ignore
            }
            classPaths.add(classPath);
            Log.debug("ClassPath: " + classPath);

        });

        //[2]修改class方法体，并保存文件
        classFiles.forEach(classFile -> {
            //解析出类全名
            String className = resolveClassName(classFile.getAbsolutePath(), true);
            byte[] bts = null;
            try {
                Log.debug("清除方法体: " + className);
                bts = ClassUtils.rewriteAllMethods(pool, className);
            } catch (Exception e) {
                Log.debug("ERROR:" + e.getMessage());
                e.printStackTrace();
            }
            if (bts != null) {
                IoUtils.writeFile(classFile, bts);
            }
        });
    }

    /**
     * 加密classes下的配置文件
     */
    private void encryptConfigFile() {
        if (this.cfgfiles == null || this.cfgfiles.size() == 0) {
            return;
        }

        //支持的框架
        //String[] supportFrame = {"spring", "jfinal"};
        String[] supportFrame = {"spring"};
        //需要注入解密功能的class
        List<File> aopClass = new ArrayList<>(supportFrame.length);

        // [1].读取配置文件时解密
        Arrays.asList(supportFrame).forEach(name -> {
            String javaCode = aopMap.get(name + ".code");
            String clazz = aopMap.get(name + ".class");
            Integer line = Integer.parseInt(aopMap.get(name + ".line"));
            javaCode = javaCode.replace("${passchar}", StrUtils.toCharArrayCode(this.password));
            byte[] bytes = null;
            try {
                String thisJar = this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
                //获取 框架 读取 配置文件的类,将密码注入该类
                bytes = ClassUtils.insertCode(clazz, javaCode, line, this.targetLibDir, new File(thisJar));
            } catch (Exception e) {
                e.printStackTrace();
                Log.debug(e.getClass().getName() + ":" + e.getMessage());
            }
            if (bytes != null) {
                File cls = new File(this.targetDir, clazz.split("#")[0] + ".class");
                IoUtils.writeFile(cls, bytes);
                aopClass.add(cls);
            }
        });

        //加密读取配置文件的类
        this.encryptClass(aopClass);
        aopClass.forEach(cls -> cls.delete());


        //[2].加密配置文件
        List<File> configFiles = new ArrayList<>();
        File[] files = this.targetClassesDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && StrUtils.isMatchs(this.cfgfiles, file.getName(), false)) {
                configFiles.add(file);
            }
        }
        //加密
        this.encryptClass(configFiles);
        //清空
        configFiles.forEach(file -> IoUtils.writeTxtFile(file, ""));
    }

    /**
     * 压缩成jar
     *
     * @return 打包后的jar绝对路径
     */
    private String packageJar(List<String> libJarFiles) {
        //[1]先打包lib下的jar
        libJarFiles.forEach(targetJar -> {
            if (!targetJar.endsWith(".jar")) {
                return;
            }

            String srcJarDir = targetJar.substring(0, targetJar.length() - 4) + Const.LIB_JAR_DIR;
            if (!new File(srcJarDir).exists()) {
                return;
            }
            JarUtils.doJar(srcJarDir, targetJar);
            IoUtils.delete(new File(srcJarDir));
            Log.debug("打包: " + targetJar);
        });

        //[2]再打包jar
        String targetJar = jarPath.replace("." + jarOrWar, "-encrypted." + jarOrWar);
        String result = JarUtils.doJar(this.targetDir.getAbsolutePath(), targetJar);
        //IoUtils.delete(this.targetDir);
        Log.debug("打包: " + targetJar);
        return result;
    }

    /**
     * 根据class的绝对路径解析出class名称或class包所在的路径
     *
     * @param fileName    class绝对路径
     * @param classOrPath true|false
     * @return class名称|包所在的路径
     */
    private String resolveClassName(String fileName, boolean classOrPath) {
        String result = resolveClassName.get(fileName + classOrPath);
        if (result != null) {
            return result;
        }
        String file = fileName.substring(0, fileName.length() - 6);
        String K_CLASSES = File.separator + "classes" + File.separator;
        String K_LIB = File.separator + "lib" + File.separator;

        String clsPath;
        String clsName;
        //lib内的的jar包
        if (file.contains(K_LIB)) {
            clsName = file.substring(file.indexOf(Const.LIB_JAR_DIR, file.indexOf(K_LIB))
                    + Const.LIB_JAR_DIR.length() + 1);
            clsPath = file.substring(0, file.length() - clsName.length() - 1);
        }
        //jar/war包-INF/classes下的class文件
        else if (file.contains(K_CLASSES)) {
            clsName = file.substring(file.indexOf(K_CLASSES) + K_CLASSES.length());
            clsPath = file.substring(0, file.length() - clsName.length() - 1);

        }
        //jar包下的class文件
        else {
            clsName = file.substring(file.indexOf(Const.LIB_JAR_DIR) + Const.LIB_JAR_DIR.length() + 1);
            clsPath = file.substring(0, file.length() - clsName.length() - 1);
        }
        result = classOrPath ? clsName.replace(File.separator, ".") : clsPath;
        resolveClassName.put(fileName + classOrPath, result);
        return result;
    }


    public Integer getEncryptFileCount() {
        return encryptFileCount;
    }

    public void setPackages(List<String> packages) {
        this.packages = packages;
    }

    public void setIncludeJars(List<String> includeJars) {
        this.includeJars = includeJars;
    }

    public void setExcludeClass(List<String> excludeClass) {
        this.excludeClass = excludeClass;
    }

    public void setClassPath(List<String> classPath) {
        this.classPath = classPath;
    }

    public void setCfgfiles(List<String> cfgfiles) {
        this.cfgfiles = cfgfiles;
    }
}

package com.spearbothy.router.plugin

import com.android.build.api.transform.*
import com.google.common.collect.Sets
import com.spearbothy.router.annotation.Autowired
import javassist.*
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

import java.lang.reflect.Constructor

class RouteAutowriedTransform extends Transform {
    Project mProject

    RouteAutowriedTransform(Project project) {
        mProject = project
    }

    @Override
    String getName() {
        return "routeAutowired"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return Collections.singleton(QualifiedContent.DefaultContentType.CLASSES)
    }

    @Override
    Set<QualifiedContent.Scope> getScopes() {
        if (mProject.plugins.hasPlugin("com.android.application")) {
            return Sets.immutableEnumSet(
                    QualifiedContent.Scope.PROJECT,
                    QualifiedContent.Scope.SUB_PROJECTS,
                    QualifiedContent.Scope.EXTERNAL_LIBRARIES)
        } else if (mProject.plugins.hasPlugin("com.android.library")) {
            return Sets.immutableEnumSet(
                    QualifiedContent.Scope.PROJECT)
        } else {
            return Collections.emptySet()
        }
    }

    @Override
    boolean isIncremental() {
        // 暂时不支持增量编译
        return false
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        ClassPool classPool = ClassPool.getDefault()

        Logger.infoLine("Router注入参数")

        def classPath = []
        // 环境变量
//        Logger.info("bootClassPath:" + mProject.android.bootClasspath[0].toString())

        classPool.appendClassPath(mProject.android.bootClasspath[0].toString())

        Class jarClassPathClazz = Class.forName("javassist.JarClassPath")
        Constructor constructor = jarClassPathClazz.getDeclaredConstructor(String.class)
        constructor.setAccessible(true)

        Logger.infoLine("扫描所有jar包，不需要处理");
        transformInvocation.inputs.each { input ->
            input.jarInputs.each { jarInput ->
                // 添加jar包，否则调用ctClass.subClass（）时会因找不到父类而返回false
                ClassPath clazzPath = (ClassPath) constructor.newInstance(jarInput.file.absolutePath)
                classPool.appendClassPath(clazzPath)
                classPath.add(clazzPath)

                def dest = transformInvocation.outputProvider.getContentLocation(jarInput.name, jarInput.contentTypes, jarInput.scopes, Format.JAR)
                FileUtils.copyFile(jarInput.file, dest)

                Logger.info("name:" + jarInput.name + " input:" + jarInput.file.getAbsolutePath() + " contentTypes" + jarInput.contentTypes + " scope" + jarInput.scopes + " output:" + dest.absolutePath)
            }
        }

        Logger.infoLine("扫描所有class，注入代码");
        transformInvocation.inputs.each { input ->
            input.directoryInputs.each { dirInput ->
                def outDir = transformInvocation.outputProvider.getContentLocation(dirInput.name, dirInput.contentTypes, dirInput.scopes, Format.DIRECTORY)
                // 获取到所有类
                classPool.appendClassPath(dirInput.file.absolutePath)
                int pathBitLen = dirInput.file.toString().length()
                Logger.info("class directory:" + dirInput.file.absolutePath);
                def callback = { File it ->
                    // 截取前置目录
                    def path = "${it.toString().substring(pathBitLen)}"
                    if (it.isDirectory()) {
                        new File(outDir, path).mkdirs()
                    } else {
                        // 文件路径
                        boolean handled = checkAndTransformClass(classPool, it, outDir)
                        if (!handled) {
                            // 如果不支持，手动拷贝到制定目录
                            new File(outDir, path).bytes = it.bytes
                        }
                    }
                }
                // 注入参数
                dirInput.file.traverse(callback);
            }
        }

        classPath.each { it -> classPool.removeClassPath(it) }
    }


    boolean checkAndTransformClass(ClassPool classPool, File file, File dest) {

//        CtClass fragmentActivityCtClass = classPool.get("android.support.v4.app.FragmentActivity")
        CtClass activityCtClass = classPool.get("android.app.Activity")

        classPool.importPackage("android.os")
        classPool.importPackage("android.util")

        if (!file.name.endsWith("class")) {
            return false
        }

        CtClass ctClass
        try {
            ctClass = classPool.makeClass(new FileInputStream(file))
        } catch (Throwable throwable) {
            Logger.error("Parsing class file ${file.getAbsolutePath()} fail.", throwable)
            return false
        }
        if (ctClass.subclassOf(activityCtClass)) {
            Logger.infoLine("${ctClass.getName()}")
            handleActivitySaveState(mProject, ctClass, classPool)
            ctClass.writeFile(dest.absolutePath)
            ctClass.detach()
            return true
        }
        return false
    }


    void handleActivitySaveState(Project project, CtClass ctClass, ClassPool classPool) {

        CtClass bundleCtClass = classPool.get("android.os.Bundle")

        // 寻找onSaveInstanceState方法
        CtMethod saveCtMethod = ctClass.declaredMethods.find {
            it.name == "onSaveInstanceState" && it.parameterTypes == [bundleCtClass] as CtClass[]
        }
        // 寻找onCreate方法
        CtMethod restoreCtMethod = ctClass.declaredMethods.find {
            it.name == "onCreate" && it.parameterTypes == [bundleCtClass] as CtClass[]
        }

        def list = []

        ctClass.declaredFields.each { field ->
            if (field.getAnnotation(Autowired.class) != null) {
                Logger.info("field ${field.name} is Autowired annotated! ")
                list.add(field)
            }
        }
        if (!list.isEmpty()) {
            Logger.info("${ctClass.simpleName} need save !")

            if (saveCtMethod == null) {
                Logger.info("${ctClass.simpleName}  add onSaveInstance method")
                // 原来的 Activity 没有 saveInstanceState 方法
                saveCtMethod = CtNewMethod.make(generateActivitySaveMethod(ctClass.name + Constant.GENERATED_FILE_SUFFIX), ctClass)
                ctClass.addMethod(saveCtMethod)
            } else {
                Logger.info("${ctClass.simpleName}  onSaveInstance exist, insert before")
                // 原来的 Activity 有 saveInstanceState 方法
                saveCtMethod.insertBefore("${ctClass.name}${Constant.GENERATED_FILE_SUFFIX}.onSaveInstanceState(this, \$1);")
            }

            if (restoreCtMethod == null) {
                Logger.info("${ctClass.simpleName}  add onCreate method")
                // 原来的 Activity 没有 onCreate 方法
                restoreCtMethod = CtNewMethod.make(generateActivityRestoreMethod(ctClass.name + Constant.GENERATED_FILE_SUFFIX), ctClass)
                ctClass.addMethod(restoreCtMethod)
            } else {
                Logger.info("${ctClass.simpleName}  onCreate exist, insert before")
                // 原来的 Activity 有 onCreate 方法
                restoreCtMethod.insertBefore("if(\$1 != null) { ${ctClass.name}${Constant.GENERATED_FILE_SUFFIX}.onRestoreInstanceState(this, \$1);} else { ${ctClass.name}${Constant.GENERATED_FILE_SUFFIX}.onRestoreInstanceState(this, getIntent().getExtras());}")
            }
        }
    }

    CtField generateEnabledField(CtClass ctClass, ClassPool classPool) {
        CtField ctField = new CtField(
                classPool.get("boolean"), Constant.ENABLE_SAVE_STATE, ctClass)
        ctField.setModifiers(Modifier.PRIVATE | Modifier.STATIC)
        return ctField
    }

    // Activity onCreate 不存在的情况下创建 onCreate 方法
    String generateActivityRestoreMethod(String delegatedName) {
        return "protected void onCreate(Bundle savedInstanceState) {\n" +
                "\tif(saveInstance == null) { " +
                "\t\t${delegatedName}.onRestoreInstanceState(this, getIntent().getExtras()); " +
                "\t} else { " +
                "\t\t${delegatedName}.onRestoreInstanceState(this, savedInstanceState); " +
                "\t}\n" +
                "super.onCreate(savedInstanceState);\n" +
                "}"
    }

    // Activity onSaveInstanceState 不存在的情况下创建 onSaveInstanceState
    String generateActivitySaveMethod(String delegatedName) {
        return "protected void onSaveInstanceState(Bundle outState) {\n" +
                "${delegatedName}.onSaveInstanceState(this, outState);" + "\n" +
                "super.onSaveInstanceState(outState);\n" +
                "}"
    }
}
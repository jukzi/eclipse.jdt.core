package org.eclipse.jdt.internal.core.builder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.AccessRuleSet;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.util.SimpleSet;
import org.eclipse.jdt.internal.compiler.util.SuffixConstants;
import org.eclipse.jdt.internal.core.util.ThreadLocalZipFiles.ThreadLocalZipFile;
import org.eclipse.jdt.internal.core.util.Util;

public class ClasspathMultiReleaseJar extends ClasspathJar {
	private static final String META_INF_VERSIONS = "META-INF/versions/"; //$NON-NLS-1$
	private static final int META_INF_LENGTH = META_INF_VERSIONS.length();
	private volatile String[] supportedVersions;

	ClasspathMultiReleaseJar(IFile resource, AccessRuleSet accessRuleSet, IPath externalAnnotationPath,
			boolean isOnModulePath, String compliance) {
		super(resource, accessRuleSet, externalAnnotationPath, isOnModulePath);
		this.compliance = compliance;
	}

	ClasspathMultiReleaseJar(String zipFilename, long lastModified, AccessRuleSet accessRuleSet,
			IPath externalAnnotationPath, boolean isOnModulePath, String compliance) {
		super(zipFilename, lastModified, accessRuleSet, externalAnnotationPath, isOnModulePath);
		this.compliance = compliance;
	}

	ClasspathMultiReleaseJar(ResourceOrExternalFile library, AccessRuleSet accessRuleSet, IPath externalAnnotationPath,
			boolean isOnModulePath, String compliance) {
		super(library, null, accessRuleSet, externalAnnotationPath, isOnModulePath);
		this.compliance = compliance;
	}

	@Override
	IModule initializeModule() {
		IModule mod = null;
		try (ThreadLocalZipFile file = createZipFile()){
			ClassFileReader classfile = null;
			try {
				for (String path : supportedVersions(file)) {
					classfile = org.eclipse.jdt.internal.core.util.Util.read(file, path.toString() + '/' + IModule.MODULE_INFO_CLASS);
					if (classfile != null) {
						break;
					}
				}

			} catch (Exception e) {
				Util.log(e, "Failed to initialize module for: " + this);  //$NON-NLS-1$
				// move on to the default
			}
			if (classfile == null) {
				classfile = org.eclipse.jdt.internal.core.util.Util.read(file, IModule.MODULE_INFO_CLASS); // FIXME: use jar cache
			}
			if (classfile != null) {
				mod = classfile.getModuleDeclaration();
			}
		} catch (ClassFormatException | IOException | CoreException e) {
			Util.log(e, "Failed to initialize module for: " + this);  //$NON-NLS-1$
		}
		return mod;
	}

	private static String[] initializeVersions(ThreadLocalZipFile zipFile, String compliance) {
		int earliestJavaVersion = ClassFileConstants.MAJOR_VERSION_9;
		long latestJDK = CompilerOptions.versionToJdkLevel(compliance);
		int latestJavaVer = (int) (latestJDK >> 16);
		List<String> versions = new ArrayList<>();
		for (int i = latestJavaVer; i >= earliestJavaVersion; i--) {
			String name = META_INF_VERSIONS + (i - 44);
			ZipEntry entry = zipFile.getEntry(name);
			if (entry != null) {
				versions.add(name);
			}
		}
		return versions.toArray(new String[versions.size()]);
	}

	private String[] supportedVersions(ThreadLocalZipFile zipFile) {
		String[] versions = this.supportedVersions;
		if (versions == null) {
			versions = initializeVersions(zipFile, this.compliance);
			this.supportedVersions = versions;
		}
		return versions;
	}

	@Override
	protected String readJarContent(final SimpleSet packageSet) {
		String modInfo = null;
		try (ThreadLocalZipFile zipFile = createZipFile()) {
			for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
				String fileName = ((ZipEntry) e.nextElement()).getName();
				if (fileName.startsWith(META_INF_VERSIONS) && fileName.length() > META_INF_LENGTH) {
					int i = fileName.indexOf('/', META_INF_LENGTH);
					fileName = fileName.substring(i + 1);
				} else if (fileName.startsWith("META-INF/")) //$NON-NLS-1$
					continue;
				if (modInfo == null) {
					int folderEnd = fileName.lastIndexOf('/');
					folderEnd += 1;
					String className = fileName.substring(folderEnd, fileName.length());
					if (className.equalsIgnoreCase(IModule.MODULE_INFO_CLASS)) {
						modInfo = fileName;
					}
				}
				addToPackageSet(packageSet, fileName, false);
			}
		} catch (CoreException e1) {
			// nothing
		}
		return modInfo;
	}

	@Override
	public NameEnvironmentAnswer findClass(String binaryFileName, String qualifiedPackageName, String moduleName,
			String qualifiedBinaryFileName, boolean asBinaryOnly, Predicate<String> moduleNameFilter) {
		if (!isPackage(qualifiedPackageName, moduleName)) {
			return null; // most common case
		}
		try (ThreadLocalZipFile zipFile = createZipFile()) {
			for (String path : supportedVersions(zipFile)) {
				String s = null;
				try {
					s = META_INF_VERSIONS + path + "/" + binaryFileName;  //$NON-NLS-1$
					ZipEntry entry =zipFile.getEntry(s);
					if (entry == null)
						continue;
					IBinaryType reader = Util.read(zipFile, s);
					if (reader != null) {
						char[] modName = this.module == null ? null : this.module.name();
						if (reader instanceof ClassFileReader) {
							ClassFileReader classReader = (ClassFileReader) reader;
							if (classReader.moduleName == null) {
								classReader.moduleName = modName;
							} else {
								modName = classReader.moduleName;
							}
						}
						String fileNameWithoutExtension = qualifiedBinaryFileName.substring(0,
								qualifiedBinaryFileName.length() - SuffixConstants.SUFFIX_CLASS.length);
						return createAnswer(fileNameWithoutExtension, reader, modName);
					}
				} catch (IOException | ClassFormatException e) {
					Util.log(e, "Failed to find class for: " + s + " in: " + this);  //$NON-NLS-1$ //$NON-NLS-2$
					// treat as if class file is missing
				}
			}
		} catch (CoreException e1) {
			// treat as if class file is missing
		}
		return super.findClass(binaryFileName, qualifiedPackageName, moduleName, qualifiedBinaryFileName, asBinaryOnly,
				moduleNameFilter);
	}

}

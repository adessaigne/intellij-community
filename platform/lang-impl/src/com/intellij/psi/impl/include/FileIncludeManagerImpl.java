/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.psi.impl.include;

import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.ParameterizedCachedValue;
import com.intellij.psi.util.ParameterizedCachedValueProvider;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import java.util.HashMap;
import com.intellij.util.containers.MultiMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Dmitry Avdeev
 */
public class FileIncludeManagerImpl extends FileIncludeManager {

  private final Project myProject;
  private final PsiManager myPsiManager;
  private final PsiFileFactory myPsiFileFactory;
  private final CachedValuesManager myCachedValuesManager;

  private final IncludeCacheHolder myIncludedHolder = new IncludeCacheHolder("compile time includes", "runtime includes") {
    @Override
    protected VirtualFile[] computeFiles(final PsiFile file, final boolean compileTimeOnly) {
      final Set<VirtualFile> files = new THashSet<>();
      processIncludes(file, info -> {
        if (compileTimeOnly != info.runtimeOnly) {
          PsiFileSystemItem item = resolveFileInclude(info, file);
          if (item != null) {
            ContainerUtil.addIfNotNull(files, item.getVirtualFile());
          }
        }
        return true;
      });
      return VfsUtilCore.toVirtualFileArray(files);
    }
  };
  private final Map<String, FileIncludeProvider> myProviderMap;

  public void processIncludes(PsiFile file, Processor<? super FileIncludeInfo> processor) {
    GlobalSearchScope scope = GlobalSearchScope.allScope(myProject);
    List<FileIncludeInfo> infoList = FileIncludeIndex.getIncludes(file.getVirtualFile(), scope);
    for (FileIncludeInfo info : infoList) {
      if (!processor.process(info)) {
        return;
      }
    }
  }

  private final IncludeCacheHolder myIncludingHolder = new IncludeCacheHolder("compile time contexts", "runtime contexts") {
    @Override
    protected VirtualFile[] computeFiles(PsiFile context, boolean compileTimeOnly) {
      final Set<VirtualFile> files = new THashSet<>();
      processIncludingFiles(context, virtualFileFileIncludeInfoPair -> {
        files.add(virtualFileFileIncludeInfoPair.first);
        return true;
      });
      return VfsUtilCore.toVirtualFileArray(files);
    }
  };

  @Override
  public void processIncludingFiles(PsiFile context, Processor<Pair<VirtualFile, FileIncludeInfo>> processor) {
    context = context.getOriginalFile();
    VirtualFile contextFile = context.getVirtualFile();
    if (contextFile == null) return;
    
    String originalName = context.getName();
    Collection<String> names = getPossibleIncludeNames(context, originalName);

    GlobalSearchScope scope = GlobalSearchScope.allScope(myProject);
    for (String name : names) {
      MultiMap<VirtualFile,FileIncludeInfoImpl> infoList = FileIncludeIndex.getIncludingFileCandidates(name, scope);
      for (VirtualFile candidate : infoList.keySet()) {
        PsiFile psiFile = myPsiManager.findFile(candidate);
        if (psiFile == null || context.equals(psiFile)) continue;
        for (FileIncludeInfo info : infoList.get(candidate)) {
          PsiFileSystemItem item = resolveFileInclude(info, psiFile);
          if (item != null && contextFile.equals(item.getVirtualFile())) {
            if (!processor.process(Pair.create(candidate, info))) {
              return;
            }
          }
        }
      }
    }
  }

  @NotNull
  private static Collection<String> getPossibleIncludeNames(@NotNull PsiFile context, @NotNull String originalName) {
    Collection<String> names = ContainerUtil.newTroveSet();
    names.add(originalName);
    for (FileIncludeProvider provider : FileIncludeProvider.EP_NAME.getExtensions()) {
      String newName = provider.getIncludeName(context, originalName);
      if (newName != originalName) {
        names.add(newName);
      }
    }
    return names;
  }

  public FileIncludeManagerImpl(Project project, PsiManager psiManager, PsiFileFactory psiFileFactory,
                                CachedValuesManager cachedValuesManager) {
    myProject = project;
    myPsiManager = psiManager;
    myPsiFileFactory = psiFileFactory;

    FileIncludeProvider[] providers = Extensions.getExtensions(FileIncludeProvider.EP_NAME);
    myProviderMap = new HashMap<>(providers.length);
    for (FileIncludeProvider provider : providers) {
      FileIncludeProvider old = myProviderMap.put(provider.getId(), provider);
      assert old == null;
    }
    myCachedValuesManager = cachedValuesManager;
  }

  @Override
  public VirtualFile[] getIncludedFiles(@NotNull VirtualFile file, boolean compileTimeOnly) {
    return getIncludedFiles(file, compileTimeOnly, false);
  }

  @Override
  public VirtualFile[] getIncludedFiles(@NotNull VirtualFile file, boolean compileTimeOnly, boolean recursively) {
    if (file instanceof VirtualFileWithId) {
      return myIncludedHolder.getAllFiles(file, compileTimeOnly, recursively);
    }
    else {
      return VirtualFile.EMPTY_ARRAY;
    }
  }

  @Override
  public VirtualFile[] getIncludingFiles(@NotNull VirtualFile file, boolean compileTimeOnly) {
    return myIncludingHolder.getAllFiles(file, compileTimeOnly, false);
  }

  @Override
  public PsiFileSystemItem resolveFileInclude(@NotNull final FileIncludeInfo info, @NotNull final PsiFile context) {
    return doResolve(info, context);
  }

  @Nullable
  private PsiFileSystemItem doResolve(@NotNull final FileIncludeInfo info, @NotNull final PsiFile context) {
    if (info instanceof FileIncludeInfoImpl) {
      String id = ((FileIncludeInfoImpl)info).providerId;
      FileIncludeProvider provider = id == null ? null : myProviderMap.get(id);
      final PsiFileSystemItem resolvedByProvider = provider == null ? null : provider.resolveIncludedFile(info, context);
      if (resolvedByProvider != null) {
        return resolvedByProvider;
      }
    }

    PsiFileImpl psiFile = (PsiFileImpl)myPsiFileFactory.createFileFromText("dummy.txt", FileTypes.PLAIN_TEXT, info.path);
    psiFile.setOriginalFile(context);
    return new FileReferenceSet(psiFile) {
      @Override
      protected boolean useIncludingFileAsContext() {
        return false;
      }
    }.resolve();
  }

  private abstract class IncludeCacheHolder {

    private final Key<ParameterizedCachedValue<VirtualFile[], PsiFile>> COMPILE_TIME_KEY;
    private final Key<ParameterizedCachedValue<VirtualFile[], PsiFile>> RUNTIME_KEY;

    private final ParameterizedCachedValueProvider<VirtualFile[], PsiFile> COMPILE_TIME_PROVIDER = new IncludedFilesProvider(true) {
      @Override
      protected VirtualFile[] computeFiles(PsiFile file, boolean compileTimeOnly) {
        return IncludeCacheHolder.this.computeFiles(file, compileTimeOnly);
      }
    };

    private final ParameterizedCachedValueProvider<VirtualFile[], PsiFile> RUNTIME_PROVIDER = new IncludedFilesProvider(false) {
      @Override
      protected VirtualFile[] computeFiles(PsiFile file, boolean compileTimeOnly) {
        return IncludeCacheHolder.this.computeFiles(file, compileTimeOnly);
      }
    };

    private IncludeCacheHolder(String compileTimeKey, String runtimeKey) {
      COMPILE_TIME_KEY = Key.create(compileTimeKey);
      RUNTIME_KEY = Key.create(runtimeKey);
    }

    @NotNull
    private VirtualFile[] getAllFiles(@NotNull VirtualFile file, boolean compileTimeOnly, boolean recursively) {
      if (recursively) {
        Set<VirtualFile> result = new HashSet<>();
        getAllFilesRecursively(file, compileTimeOnly, result);
        return VfsUtilCore.toVirtualFileArray(result);
      }
      return getFiles(file, compileTimeOnly);
    }

    private void getAllFilesRecursively(@NotNull VirtualFile file, boolean compileTimeOnly, Set<? super VirtualFile> result) {
      if (!result.add(file)) return;
      VirtualFile[] includes = getFiles(file, compileTimeOnly);
      if (includes.length != 0) {
        for (VirtualFile include : includes) {
          getAllFilesRecursively(include, compileTimeOnly, result);
        }
      }
    }

    private VirtualFile[] getFiles(@NotNull VirtualFile file, boolean compileTimeOnly) {
      PsiFile psiFile = myPsiManager.findFile(file);
      if (psiFile == null) {
        return VirtualFile.EMPTY_ARRAY;
      }
      if (compileTimeOnly) {
        return myCachedValuesManager.getParameterizedCachedValue(psiFile, COMPILE_TIME_KEY, COMPILE_TIME_PROVIDER, false, psiFile);
      }
      return myCachedValuesManager.getParameterizedCachedValue(psiFile, RUNTIME_KEY, RUNTIME_PROVIDER, false, psiFile);
    }

    protected abstract VirtualFile[] computeFiles(PsiFile file, boolean compileTimeOnly);

  }

  private abstract static class IncludedFilesProvider implements ParameterizedCachedValueProvider<VirtualFile[], PsiFile> {
    private final boolean myRuntimeOnly;

    IncludedFilesProvider(boolean runtimeOnly) {
      myRuntimeOnly = runtimeOnly;
    }

    protected abstract VirtualFile[] computeFiles(PsiFile file, boolean compileTimeOnly);

    @Override
    public CachedValueProvider.Result<VirtualFile[]> compute(PsiFile psiFile) {
      VirtualFile[] value = computeFiles(psiFile, myRuntimeOnly);
      // todo: we need "url modification tracker" for VirtualFile
      List<Object> deps = new ArrayList<>(Arrays.asList(value));
      deps.add(psiFile);
      deps.add(VirtualFileManager.getInstance());

      return CachedValueProvider.Result.create(value, deps);
    }
  }
}

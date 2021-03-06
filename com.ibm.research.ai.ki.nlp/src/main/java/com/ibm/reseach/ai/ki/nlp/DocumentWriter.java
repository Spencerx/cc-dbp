/**
 * cc-dbp-dataset
 *
 * Copyright (c) 2017 IBM
 *
 * The author licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.reseach.ai.ki.nlp;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import com.ibm.research.ai.ki.util.*;

/**
 * Use "try (DocumentWriter writer = new DocumentWriter(outputDirectory)) {..."
 * to take advantage of AutoClosable
 * @author mrglass
 *
 */
public class DocumentWriter implements AutoCloseable {
	public static final int FILES_PER_DIR = 100;
	private static final int lpadLen = (int)Math.ceil(Math.log10(FILES_PER_DIR));
	
	public static final String partialExt = ".partial.ser.gz";
	public static final String finishedExt = ".ser.gz";
	
	protected int documentsPerFile;
	protected String currentRelativeFile = null;
	protected int documentsInFile = 0;
	protected File rootDir;
	protected ArrayList<MutableInteger> currentDirectoryCounts = new ArrayList<>();
	protected ObjectOutputStream oos = null;
	protected boolean overwrite;
	//also automatically creates subdirectories (and move already written files to a first subdir)

	//CONSIDER:
	//could use ObjectOutputStream.annotateClass to gather List<Class> writtenClasses;
	//  then foreach Class x : writtenClasses, get its location with x.class.getResource(x.getSimpleName()+".class")
	//  then the classpath required to load the classes could be saved in the DocumentCollection metadata	
	
	public DocumentWriter(File rootDir, int documentsPerFile, boolean overwrite) {
		this.rootDir = rootDir;
		this.documentsPerFile = documentsPerFile;
		currentDirectoryCounts.add(new MutableInteger(0));
		this.overwrite = overwrite;
		if (rootDir.exists() && !rootDir.isDirectory())
			throw new IllegalArgumentException("Not a directory! "+rootDir);
		if (rootDir.exists() && rootDir.listFiles().length > 0 && !overwrite)
			throw new IllegalArgumentException("Directory not empty! "+rootDir);
	}
	
	public DocumentWriter(File rootDir) {
		this(rootDir, 1000, false);
	}
	
	public synchronized void write(Document doc) {	
		try {
			if (rootDir == null)
				throw new Error("already closed");
			if (oos == null)
				oos = makeStream();
			if (documentsInFile >= documentsPerFile)
				nextObjectStream();
			oos.writeObject(doc);
			
			++documentsInFile;
		} catch (Exception e) {
			Lang.error(e);
		}
	}
	
	@Override
	public synchronized void close() {
		try {
			if (oos != null) {
				oos.close();
				Files.move(
						Paths.get(rootDir.getAbsolutePath(), currentRelativeFile + partialExt), 
						Paths.get(rootDir.getAbsolutePath(), currentRelativeFile + finishedExt));
			}
			oos = null;
			rootDir = null;
		} catch (Exception e) {
			//ignore
		}
	}
	
	protected void nextObjectStream() {
		try {
			oos.close();
			Files.move(
					Paths.get(rootDir.getAbsolutePath(), currentRelativeFile + partialExt), 
					Paths.get(rootDir.getAbsolutePath(), currentRelativeFile + finishedExt));

			for (MutableInteger c : currentDirectoryCounts) {
				c.value += 1;
				if (c.value >= FILES_PER_DIR)
					c.value = 0; //we will create new dir
				else
					break;
			}
			if (currentDirectoryCounts.get(currentDirectoryCounts.size()-1).value == 0)
				deepenDirectories();
			documentsInFile = 0;
			oos = makeStream();
			
		} catch (Exception e) {
			Lang.error(e);
		}
	}
	
	static String dirName(int dirNum) {
		return Lang.LPAD(String.valueOf(dirNum),'0', lpadLen);
	}
	
	protected ObjectOutputStream makeStream() throws Exception {
		StringBuilder buf = new StringBuilder();
		for (int i = currentDirectoryCounts.size()-1; i >= 0; --i) 
			buf.append(dirName(currentDirectoryCounts.get(i).value)).append(File.separator);
		buf.setLength(buf.length()-1);
		currentRelativeFile = buf.toString();
		String baseFilename = FileUtil.ensureSlash(rootDir.getAbsolutePath()) + currentRelativeFile;
		
		if (!overwrite && (
				FileUtil.exists(baseFilename + finishedExt) ||
				FileUtil.exists(baseFilename + partialExt)))
			throw new Error("File already exists: "+baseFilename);
		
		File file = new File(baseFilename + partialExt);
		FileUtil.ensureWriteable(file);
		return new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(file), 2 << 16));
	}
	
	protected void deepenDirectories() {
		try {
			//CONSIDER: but if we create a preprocessedIndex as we go, we will need to update file locations
			Path tempRoot = Paths.get(rootDir.getParent(), rootDir.getName() + "___temp");
			if (FileUtil.exists(tempRoot.toString()))
				throw new Error("temp dir for deepening exists!");
			Files.move(Paths.get(rootDir.getAbsolutePath()), tempRoot);
			rootDir.mkdirs();
			Files.move(tempRoot, Paths.get(rootDir.getAbsolutePath(), dirName(0)));
			currentDirectoryCounts.add(new MutableInteger(1));
		} catch (Exception e) {
			Lang.error(e);
		}
	}
	
	//TODO: main method for conversion (ser.gz -> json and others)
}

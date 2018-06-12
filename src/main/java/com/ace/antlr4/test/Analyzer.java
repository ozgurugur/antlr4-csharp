package com.ace.antlr4.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;

public class Analyzer {

	public static class Scan {
		public static class Folder {
			public static enum TYPE {
				ORDINARY, SCREEN
			}

			public static class File {

				public static class Component {
					public static class Usage {
						Set<String> screens = new HashSet<String>();
						int used = 0;

						public void usedInScreen(String screen, int used) {
							screens.add(screen);
							this.used = this.used + used;
						}
					}

					String name;
					Usage usage = new Usage();
				}

				public static enum TYPE {
					OTHER, CLASS, DESIGNER_CLASS
				}

				public static enum STATUS {
					PARSED, ERRORNOUS, IGNORED;
				}

				public TYPE type;
				public STATUS status = STATUS.IGNORED;
				public String name;
				public int length;
				public HashMap<String, Scan.Folder.File.Component> components = new HashMap<String, Scan.Folder.File.Component>();
				public String error = "n/a";
				public String path;

			}

			public TYPE type;
			public String name;

			List<Folder> folders = new ArrayList<Folder>();
			List<File> files = new ArrayList<File>();
		}

		public List<Folder> folders = new ArrayList<Folder>();

		public HashMap<String, Scan.Folder.File.Component> components = new HashMap<String, Scan.Folder.File.Component>();

		public void addComponentUsage(Scan.Folder.File.Component screenComponent, String screen) {
			Scan.Folder.File.Component component;
			if ((component = components.get(screenComponent.name)) == null) {
				component = new Scan.Folder.File.Component();
				component.name = screenComponent.name;
				components.put(screenComponent.name, component);
			}
			component.usage.usedInScreen(screen, screenComponent.usage.used);
		}

	}

	public static void main(String[] args) throws NumberFormatException, IOException {
		// doAll(args);
		Scan scan = new Scan();
		File rptBase = new File(args[1]);
		rptBase.mkdirs();
		visitFolder4Analsys(scan, new File(args[0]), rptBase);
		PrintStream out = new PrintStream(new FileOutputStream(new File(rptBase, "	analysis.log"), false), true) {
			@Override
			public void println(String x) {
				System.out.println(x);
				super.println(x);
			}

			@Override
			public void print(String s) {
				System.out.print(s);
				super.print(s);
			}
		};
		out.println("-----------------------------------------------------");
		out.println("Component;Usage;DistinctScreenCount;Complexity");
		for (Scan.Folder.File.Component component : scan.components.values()) {
			out.print(component.name);
			out.print(";");
			out.print(component.usage.used);
			out.print(";");
			out.print(component.usage.screens.size());
			out.println(";");
		}
		out.println("-----------------------------------------------------");
		out.println("PATH;SCREEN;FILE;TYPE;length;STATUS;ClassComponentName;ClassComponentUsage;Error;");
		for (Scan.Folder folder : scan.folders) {
			visitFolder4Report(File.pathSeparator, folder, out);
		}

	}

	private static void visitFolder4Report(String path, Scan.Folder folder, PrintStream out) {
		String folderName = folder.name;
		if (folder.type == Scan.Folder.TYPE.ORDINARY) {
			for (Scan.Folder subFolder : folder.folders) {
				visitFolder4Report(path + folderName + File.pathSeparator, subFolder, out);
			}
		} else {
			for (Scan.Folder.File file : folder.files) {
				if (file.type == Scan.Folder.File.TYPE.DESIGNER_CLASS) {
					if (file.status == Scan.Folder.File.STATUS.PARSED) {
						for (Scan.Folder.File.Component component : file.components.values()) {
							out.print(file.path);
							out.print(";");
							out.print(folder.name);
							out.print(";");
							out.print(file.name);
							out.print(";");
							out.print(file.type);
							out.print(";");
							out.print(file.length);
							out.print(";");
							out.print(file.status);
							out.print(";");
							out.print(component.name);
							out.print(";");
							out.print(component.usage.used);
							out.print(";");
							out.print(file.error);
							out.println(";");

						}
					} else {
						out.print(file.path);
						out.print(";");
						out.print(folder.name);
						out.print(";");
						out.print(file.name);
						out.print(";");
						out.print(file.type);
						out.print(";");
						out.print(file.length);
						out.print(";");
						out.print(file.status);
						out.print(";");
						out.print("n/a");
						out.print(";");
						out.print("");
						out.print(";");
						out.print(file.error);
						out.println(";");
					}
				} else {
					out.print(file.path);
					out.print(";");
					out.print(folder.name);
					out.print(";");
					out.print(file.name);
					out.print(";");
					out.print(file.type);
					out.print(";");
					out.print(file.length);
					out.print(";");
					out.print(file.status);
					out.print(";");
					out.print("n/a");
					out.print(";");
					out.print("");
					out.print(";");
					out.print(file.error);
					out.println(";");
				}
			}
		}
	}

	private static void visitFolder4Analsys(Scan scan, File lkpBase, File rptBase) throws IOException {
		// TODO Auto-generated method stub
		if (!rptBase.exists()) {
			rptBase.mkdirs();
		}
		//
		String fileName;
		System.out.println("Scanning folder>" + lkpBase.getPath());
		HashMap<String, File> folders = new HashMap<String, File>();
		HashMap<String, File> files = new HashMap<String, File>();
		// process files first
		for (File file : lkpBase.listFiles()) {
			fileName = file.getName();
			if (file.isDirectory()) {
				folders.put(fileName, file);
				// browse(new File(lkpBase, fileName), new File(rptBase, fileName));
			} else if (file.length() > 0) {
				files.put(fileName, file);
			}
		}
		// process
		// lookup dir-parse.log
		Scan.Folder scanFolder;
		Scan.Folder.File scanFile;
		Scan.Folder.File.Component component;
		String content, screen, screenFolder, componentName;
		List<String> lines, sizeLines, parsedLines;
		String[] splited;
		File file, classParseFolder, parseFile, errorFile;
		if ((file = files.remove("dir-parse.log")) != null) {
			content = IOUtils.toString(new FileInputStream(file));
			if (content.contains("Parsing class")) {
				// screen-folder
				scanFolder = new Scan.Folder();
				scanFolder.name = lkpBase.getName();
				scanFolder.type = Scan.Folder.TYPE.SCREEN;
				System.out.println("Screen folder:" + lkpBase.getPath());
				// read size log
				sizeLines = IOUtils.readLines(new FileInputStream(files.remove("dir-size.log")));
				for (String sizeLine : sizeLines) {
					// FORMAT[File:formBKRTBSYN.Designer.cs>67492]
					if (sizeLine.startsWith("File:")) {
						sizeLine = sizeLine.trim().split(":")[1];
						scanFile = new Scan.Folder.File();
						splited = sizeLine.split(">");
						scanFile.name = splited[0];
						scanFile.length = Integer.parseInt(splited[1]);
						scanFile.path = file.getParentFile().getPath();
						if (content.contains("Parsing class>" + scanFile.name)) {
							scanFile.type = Scan.Folder.File.TYPE.DESIGNER_CLASS;
							screenFolder = scanFile.name;
							System.out.println("parsed-class-folder:" + screenFolder);
							// remove from ordinary unchecked folders list
							folders.remove(screenFolder);
							// read parsed component info
							classParseFolder = new File(lkpBase, screenFolder);
							if (classParseFolder.exists()) {
								errorFile = new File(classParseFolder, scanFile.name + "-parse-failed.txt");
								if (errorFile.exists() && errorFile.length() > 0) {
									// mark as errornous and discarded
									scanFile.status = Scan.Folder.File.STATUS.ERRORNOUS;
								} else {
									// read component info to calc complexity
									parseFile = new File(classParseFolder, "gui-comp-tree.txt");
									if (parseFile.exists() && parseFile.length() > 0) {
										scanFile.status = Scan.Folder.File.STATUS.PARSED;
										parsedLines = IOUtils.readLines(new FileInputStream(parseFile));
										for (String parsedLine : parsedLines) {
											// ignore first line goon
											if (!parsedLine.startsWith(">>page")) {
												// ucSubeNakleden(t:Fintek.UI.UserControls.Sube,x:8,y:41,w:344,h:24)
												if (parsedLine.contains("(")) {
													splited = parsedLine.split("\\(");
													// t:Fintek.UI.UserControls.Sube,x:8,y:41,w:344,h:24)
													splited = splited[1].split(",");
													splited = splited[0].split(":");
													componentName = splited[1];
													// find component usagecmd
													if ((component = scanFile.components.get(componentName)) == null) {
														component = new Scan.Folder.File.Component();
														component.name = componentName;
														scanFile.components.put(componentName, component);
													}
													component.usage.usedInScreen(scanFile.name, 1);
												} else {
													// unkowncomponent
													scanFile.status = Scan.Folder.File.STATUS.ERRORNOUS;
													scanFile.error = "unkown-component-type:" + parsedLine + ";";
												}
											}
										}
										// merge page component usage to global usage
										if (scanFile.status != Scan.Folder.File.STATUS.ERRORNOUS) {
											for (Scan.Folder.File.Component screenComponent : scanFile.components
													.values()) {
												scan.addComponentUsage(screenComponent, scanFile.name);
											}
										}

									} else {
										// unexpected state
									}
								}
							} else {
								// unexpected state
							}
						} else {
							scanFile.type = Scan.Folder.File.TYPE.OTHER;
						}
						scanFolder.files.add(scanFile);
					}
				}

			} else {
				// ordinary or failed folder
				scanFolder = new Scan.Folder();
				scanFolder.name = lkpBase.getName();
				scanFolder.type = Scan.Folder.TYPE.ORDINARY;
				System.out.println("Ordinary folder:" + lkpBase.getPath());
			}
			scan.folders.add(scanFolder);
		}
		// scan renaming unchecked ordinary folders
		for (File folder : folders.values()) {
			visitFolder4Analsys(scan, folder, new File(rptBase, folder.getName()));
		}
	}

}

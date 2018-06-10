package com.ace.antlr4.test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.antlr.v4.runtime.ANTLRFileStream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DiagnosticErrorListener;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.PredictionMode;

import com.ace.antlr4.csharp.CSharpLexer;
import com.ace.antlr4.csharp.CSharpParser;
import com.ace.antlr4.test.Test.Compilation;
import com.ace.antlr4.test.Test.Component;
import com.ace.antlr4.test.Test.Container;
import com.ace.antlr4.test.Test.KPI;
import com.ace.antlr4.test.Test.Worker;
import com.ace.antlr4.test.Test.Compilation.Visitor;

public class Analyzer {
	private static final String SIGNATURE_TEXT = ".Text =";
	private static final String SIGNATURE_SYSTEM_DRAWING_SIZE = "System.Drawing.Size";
	private static final String SIGNATURE_SYSTEM_DRAWING_POINT = "System.Drawing.Point";
	private static final String ASSIGNMENT_LOCATION = ".Location =";
	private static final String ASSIGNMENT_NEW = " = new";
	private static final String INSTRUCTION_CONTAINER_ADD_COMPONENT = ".Controls.Add(";
	private static final String METHOD_HEADER_NITIALIZE_COMPONENT = "private void InitializeComponent()";
	// public static long lexerTime = 0;
	public static boolean profile = false;
	public static boolean notree = false;
	public static boolean gui = false;
	public static boolean printTree = false;
	public static boolean SLL = false;
	public static boolean diag = false;
	public static boolean bail = false;
	public static boolean x2 = false;
	public static boolean threaded = false;
	public static boolean quiet = false;
	// public static long parserStart;
	// public static long parserStop;
	public static Worker[] workers = new Worker[3];
	static int windex = 0;

	public static CyclicBarrier barrier;

	public static volatile boolean firstPassDone = false;

	public static class Worker implements Runnable {
		public long parserStart;
		public long parserStop;
		List<String> files;

		public Worker(List<String> files) {
			this.files = files;
		}

		// @Override
		public void run() {
			parserStart = System.currentTimeMillis();
			for (String f : files) {
				parseFile(f);
			}
			parserStop = System.currentTimeMillis();
			try {
				barrier.await();
			} catch (InterruptedException ex) {
				return;
			} catch (BrokenBarrierException ex) {
				return;
			}
		}
	}

	public static void doAll(String[] args) {
		List<String> inputFiles = new ArrayList<String>();
		long start = System.currentTimeMillis();
		try {
			if (args.length > 0) {
				// for each directory/file specified on the command line
				for (int i = 0; i < args.length; i++) {
					if (args[i].equals("-notree"))
						notree = true;
					else if (args[i].equals("-gui"))
						gui = true;
					else if (args[i].equals("-ptree"))
						printTree = true;
					else if (args[i].equals("-SLL"))
						SLL = true;
					else if (args[i].equals("-bail"))
						bail = true;
					else if (args[i].equals("-diag"))
						diag = true;
					else if (args[i].equals("-2x"))
						x2 = true;
					else if (args[i].equals("-threaded"))
						threaded = true;
					else if (args[i].equals("-quiet"))
						quiet = true;
					if (args[i].charAt(0) != '-') { // input file name
						inputFiles.add(args[i]);
					}
				}
				List<String> javaFiles = new ArrayList<String>();
				for (String fileName : inputFiles) {
					List<String> files = getFilenames(new File(fileName));
					javaFiles.addAll(files);
				}
				doFiles(javaFiles);

				// DOTGenerator gen = new DOTGenerator(null);
				// String dot = gen.getDOT(Java8Parser._decisionToDFA[112], false);
				// System.out.println(dot);
				// dot = gen.getDOT(Java8Parser._decisionToDFA[81], false);
				// System.out.println(dot);

				if (x2) {
					System.gc();
					System.out.println("waiting for 1st pass");
					if (threaded)
						while (!firstPassDone) {
						} // spin
					System.out.println("2nd pass");
					doFiles(javaFiles);
				}
			} else {
				System.err.println("Usage: java Main <directory or file name>");
			}
		} catch (Exception e) {
			System.err.println("exception: " + e);
			e.printStackTrace(System.err); // so we can get stack trace
		}
		long stop = System.currentTimeMillis();
		// System.out.println("Overall time " + (stop - start) + "ms.");
		System.gc();
	}

	public static void doFiles(List<String> files) throws Exception {
		long parserStart = System.currentTimeMillis();
		// lexerTime = 0;
		if (threaded) {
			barrier = new CyclicBarrier(3, new Runnable() {
				public void run() {
					report();
					firstPassDone = true;
				}
			});
			int chunkSize = files.size() / 3; // 10/3 = 3
			int p1 = chunkSize; // 0..3
			int p2 = 2 * chunkSize; // 4..6, then 7..10
			workers[0] = new Worker(files.subList(0, p1 + 1));
			workers[1] = new Worker(files.subList(p1 + 1, p2 + 1));
			workers[2] = new Worker(files.subList(p2 + 1, files.size()));
			new Thread(workers[0], "worker-" + windex++).start();
			new Thread(workers[1], "worker-" + windex++).start();
			new Thread(workers[2], "worker-" + windex++).start();
		} else {
			for (String f : files) {
				parseFile(f);
			}
			long parserStop = System.currentTimeMillis();
			System.out.println("Total lexer+parser time " + (parserStop - parserStart) + "ms.");
		}
	}

	private static void report() {
		// parserStop = System.currentTimeMillis();
		// System.out.println("Lexer total time " + lexerTime + "ms.");
		long time = 0;
		if (workers != null) {
			// compute max as it's overlapped time
			for (Worker w : workers) {
				long wtime = w.parserStop - w.parserStart;
				time = Math.max(time, wtime);
				System.out.println("worker time " + wtime + "ms.");
			}
		}
		System.out.println("Total lexer+parser time " + time + "ms.");

		System.out.println("finished parsing OK");
		System.out.println(LexerATNSimulator.match_calls + " lexer match calls");
		// System.out.println(ParserATNSimulator.predict_calls +" parser predict
		// calls");
		// System.out.println(ParserATNSimulator.retry_with_context +"
		// retry_with_context after SLL conflict");
		// System.out.println(ParserATNSimulator.retry_with_context_indicates_no_conflict
		// +" retry sees no conflict");
		// System.out.println(ParserATNSimulator.retry_with_context_predicts_same_alt +"
		// retry predicts same alt as resolving conflict");
	}

	public static List<String> getFilenames(File f) throws Exception {
		List<String> files = new ArrayList<String>();
		getFilenames_(f, files);
		return files;
	}

	public static void getFilenames_(File f, List<String> files) throws Exception {
		// If this is a directory, walk each file/dir in that directory
		if (f.isDirectory()) {
			String flist[] = f.list();
			for (int i = 0; i < flist.length; i++) {
				getFilenames_(new File(f, flist[i]), files);
			}
		}

		// otherwise, if this is a csharp file, parse it!
		else if (((f.getName().length() > 3) && f.getName().substring(f.getName().length() - 3).equals(".cs"))) {
			files.add(f.getAbsolutePath());
		}
	}

	// This method decides what action to take based on the type of
	// file we are looking at
	// public static void doFile_(File f) throws Exception {
	// // If this is a directory, walk each file/dir in that directory
	// if (f.isDirectory()) {
	// String files[] = f.list();
	// for(int i=0; i < files.length; i++) {
	// doFile_(new File(f, files[i]));
	// }
	// }
	//
	// // otherwise, if this is a java file, parse it!
	// else if ( ((f.getName().length()>5) &&
	// f.getName().substring(f.getName().length()-5).equals(".java")) )
	// {
	// System.err.println(f.getAbsolutePath());
	// parseFile(f.getAbsolutePath());
	// }
	// }

	public static void hash(String content, File toFile) throws IOException {

		FileOutputStream fos = new FileOutputStream(toFile);
		ZipOutputStream zipOut = new ZipOutputStream(fos);
		ZipEntry zipEntry = new ZipEntry("init");
		zipOut.putNextEntry(zipEntry);
		zipOut.write(content.getBytes("utf-8"));
		zipOut.close();
		fos.flush();
		fos.close();
	}

	public static void parseFile(String f) {
		try {
			if (!quiet)
				System.err.println(f);
			// Create a scanner that reads from the input stream passed to us
			Lexer lexer = new CSharpLexer(new ANTLRFileStream(f));

			CommonTokenStream tokens = new CommonTokenStream(lexer);
			// long start = System.currentTimeMillis();
			// tokens.fill(); // load all and check time
			// long stop = System.currentTimeMillis();
			// lexerTime += stop-start;

			// Create a parser that reads from the scanner
			CSharpParser parser = new CSharpParser(tokens);
			if (diag)
				parser.addErrorListener(new DiagnosticErrorListener());
			if (bail)
				parser.setErrorHandler(new BailErrorStrategy());
			if (SLL)
				parser.getInterpreter().setPredictionMode(PredictionMode.SLL);

			// start parsing at the compilationUnit rule
			ParserRuleContext t = parser.compilation_unit();

			// CSharpParser.Compilation_unitContext tree = parser.compilation_unit(); //
			// parse a compilationUnit

			if (notree)
				parser.setBuildParseTree(false);
			// if ( gui ) t inspect(parser);
			if (printTree)
				System.out.println(t.toStringTree(parser));

			quickParse(new File(f));

		} catch (Exception e) {
			System.err.println("parser exception: " + e);
			e.printStackTrace(); // so we can get stack trace
		}
	}

	public static void main(String[] args) throws FileNotFoundException {
		// doAll(args);

		browse(new File(args[0]), new File(args[1]), Integer.parseInt(args[2]));
	}

	static final int CMD_PARSE_CS = 1;
	static final int CMD_LENGTH_FILE = 2;

	private static void browse(File lkpBase, File rptBase, int cmd) throws FileNotFoundException {
		// TODO Auto-generated method stub
		if (!rptBase.exists()) {
			rptBase.mkdirs();
		}
		String fileName;
		PrintWriter logWriter = new PrintWriter(new FileOutputStream(new File(rptBase, "dir-"+(cmd == CMD_LENGTH_FILE?"size":"parse")+".log")), true);
		PrintWriter errWriter = new PrintWriter(new FileOutputStream(new File(rptBase, "dir-"+(cmd == CMD_LENGTH_FILE?"size":"parse")+".err")), true);
		logWriter.append("Scanning folder>").append(lkpBase.getPath()).append("\n");
		System.out.println("Scanning folder>" + lkpBase.getPath());
		logWriter.flush();
		List<String> folderNamesFound = new ArrayList<String>();
		// process files first
		for (File file : lkpBase.listFiles()) {
			fileName = file.getName();
			if (file.isDirectory()) {
				folderNamesFound.add(fileName);
				// browse(new File(lkpBase, fileName), new File(rptBase, fileName));
			} else {
				if ((cmd & CMD_LENGTH_FILE) == CMD_LENGTH_FILE) {
					logWriter.append("File:").append(fileName).append(">").append(String.valueOf(file.length()))
							.append(" \n");
					logWriter.flush();
				} else {
					if (fileName.endsWith(".cs")) {
						System.out.println("Parsing class>" + fileName);
						logWriter.append("Parsing class>").append(fileName).append("\n");
						logWriter.flush();
						try {
							quickParse(file, new File(rptBase, fileName), logWriter, errWriter);
						} catch (Exception e) {
							try {
								PrintWriter writer = new PrintWriter(new File(rptBase, fileName + ".p.err.txt"));
								e.printStackTrace(writer);
								writer.flush();
								writer.close();
							} catch (FileNotFoundException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
							}
						}
					} else {
						logWriter.append("File ignored>").append(fileName).append("\n");
						logWriter.flush();
					}
				}
			}
		}
		logWriter.flush();
		logWriter.close();
		errWriter.flush();
		errWriter.close();
		// process folders found
		for (String folderName : folderNamesFound) {
			browse(new File(lkpBase, folderName), new File(rptBase, folderName),cmd);
		}
	}


}

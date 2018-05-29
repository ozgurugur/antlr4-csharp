package com.ace.antlr4.test;
/*
 [The "BSD license"]
  Copyright (c) 2013 Terence Parr
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions
  are met:

  1. Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
  2. Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
  3. The name of the author may not be used to endorse or promote products
     derived from this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

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

/* This more or less duplicates the functionality of grun (TestRig) but it
 * has a few specific options for benchmarking like -x2 and -threaded.
 * It also allows directory names as commandline arguments. The simplest test is
 * for the current directory:

~/antlr/code/grammars-v4/java $ java Test .
/Users/parrt/antlr/code/grammars-v4/java8/JavaBaseListener.java
/Users/parrt/antlr/code/grammars-v4/java8/Java8Lexer.java
/Users/parrt/antlr/code/grammars-v4/java8/JavaListener.java
/Users/parrt/antlr/code/grammars-v4/java8/JavaParser.java
/Users/parrt/antlr/code/grammars-v4/java8/Test.java
Total lexer+parser time 1867ms.
 */
public class Test {
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

	public static void main(String[] args) {
		doAll(args);
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

			quickParse(f);

		} catch (Exception e) {
			System.err.println("parser exception: " + e);
			e.printStackTrace(); // so we can get stack trace
		}
	}

	static class Compilation {
		File file;
		String initMethodSource;

		Map<String, Component> components = new HashMap<String, Component>();
		Map<String, Container> containers = new HashMap<String, Container>();

		Compilation(String f) {
			this.file = new File(f);
		}

		public static interface Visitor {
			void enter(Container container, int depth) throws IOException;

			void exit(Container container, int depth) throws IOException;

			void enter(Component component, int depth) throws IOException;

			void exit(Component component, int depth) throws IOException;

		}

		public void visitTree(boolean dumpDetailed, Visitor... actions) throws IOException {
			for (Container container : containers.values()) {
				if (container.container == null) {
					for (Visitor action : actions) {
						action.enter(container, 1);
					}
					System.out.println(">>" + (container.name.equals("this") ? "page" : container.name.substring(5)));
					traverse(dumpDetailed, 2, container.components.values(), actions);
					for (Visitor action : actions) {
						action.exit(container, 1);
					}
				}
			}
		}

		private void traverse(boolean dumpDetailed, int depth, Collection<Component> components, Visitor... actions)
				throws IOException {
			for (Component component : components) {
				System.out.print(
						">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>".substring(0, depth * 2) + component.name.substring(5));
				if (dumpDetailed) {
					System.out.print("(t:" + component.type);
					System.out.print(",x:" + component.x);
					System.out.print(",y:" + component.y);
					System.out.print(",w:" + component.w);
					System.out.println(",h:" + component.h + ")");
				} else {
					System.out.println();
				}
				if (component instanceof Container) {
					for (Visitor action : actions) {
						action.enter(((Container) component), depth);
					}
					traverse(dumpDetailed, depth + 1, ((Container) component).components.values(), actions);
					for (Visitor action : actions) {
						action.exit(((Container) component), depth);
					}
				} else {
					for (Visitor action : actions) {
						action.enter(component, depth);
					}
					for (Visitor action : actions) {
						action.exit(component, depth);
					}
				}
			}
		}

	}

	static class Component {
		// first pass variablesy()
		Container container;
		String name;

		// second pass variables
		int x = 0, y = 0;
		int w = 0, h = 0;

		String label;
		String type;
		String initializerLine;
		String instructionLines;
		String assignmentLines;
	}

	static class Container extends Component {
		public Container() {
		}

		public Container(Component c) {
			// clone first pass
			super.name = c.name;
			super.container = c.container;
		}

		Map<String, Component> components = new LinkedHashMap<String, Component>();
	}

	private static void quickParse(String f) throws IOException {
		Compilation compilation = new Compilation(f);
		System.out.println("-----------first-pass----------------------");
		firstPass(compilation);
		System.out.println(compilation.initMethodSource);
		System.out.println("*********first-pass************************");
		compilation.visitTree(false);
		System.out.println("-----------second-pass---------------------");
		secondPass(compilation);
		System.out.println("*********second-pass***********************");
		compilation.visitTree(false);
		System.out.println("*********detailed-dump***********************");
		compilation.visitTree(true);
		System.out.println("*********export-2-html5***********************");
		System.out.println(exportHtml5(compilation));

	}

	private static void firstPass(Compilation compilation) {
		StringBuilder buf = new StringBuilder(8546);
		try {
			Map<String, Container> containers = compilation.containers;
			Map<String, Component> components = compilation.components;
			Container container;
			Component component;
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(compilation.file)));
			// Pattern commentOut = Pattern.compile("/s//.*");
			String line;
			boolean parsingInitMethod = false;
			int openBracket = 0;
			int i1, i2, i3;
			String t1, t2, t3;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.contains(METHOD_HEADER_NITIALIZE_COMPONENT)) {
					parsingInitMethod = true;
					lineNumber = 0;
				}
				if (parsingInitMethod) {
					lineNumber++;
					if (!line.startsWith("//")) {// not comment out
						// System.out.println(line);
						buf.append(line).append("\n");
					}
					// detect components add containers
					if (line.contains(".Controls.Add(")) {
						i1 = line.indexOf(".Controls.Add(") + 14;
						i2 = line.indexOf(")", i1 + 1);
						t1 = line.substring(i1, i2);
						t2 = line.substring(0, i1 - 14);
						System.out.println("COMPONENT:" + t1 + " add to CONTAINER:" + t2);
						// add new container or fetch
						container = null;
						if (containers.containsKey(t2)) {
							container = containers.get(t2);
						} else {
							if (components.containsKey(t2)) {// container added as a component before
								component = components.remove(t2);
								component.container.components.remove(component.name);
								container = new Container(component);
								component.container.components.put(container.name, container);
							} else {
								container = new Container();
							}
							container.name = t2;
							containers.put(t2, container);
						}
						// add new component or fetch
						component = null;
						if (containers.containsKey(t1)) {// check if its a comtainer added before
							component = containers.get(t1);
							component.container = container;
							// if so set parent container
						} else if (components.containsKey(t1)) {
							// check if its a component added before
							throw new RuntimeException("component(" + t1 + ") is duplicated!!");
						} else {// new component
							component = new Component();
							component.name = t1;
							components.put(t1, component);
						}
						container.components.put(t1, component);
						component.container = container;
					}
					// final bracket control
					if (line.contains("{")) {
						openBracket++;
					} else if (line.contains("}")) {
						openBracket--;
					}
					// check method exists
					if (openBracket == 0 && lineNumber > 2) {
						parsingInitMethod = false;
					}
				}

			}

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		compilation.initMethodSource = buf.toString();
	}

	private static void secondPass(final Compilation compilation) {

		try {
			compilation.visitTree(false, new Compilation.Visitor() {

				@Override
				public void enter(Container container, int depth) throws IOException {
					extractPass2Values(container);

				}

				@Override
				public void enter(Component component, int depth) throws IOException {
					extractPass2Values(component);

				}

				@Override
				public void exit(Container container, int depth) throws IOException {
					// TODO Auto-generated method stub

				}

				@Override
				public void exit(Component component, int depth) throws IOException {
					// TODO Auto-generated method stub

				}

				private String extractTOken(String line, String begin, String end) {
					try {
						int i1, i2;
						i1 = line.indexOf(begin) + begin.length();
						i2 = line.indexOf(end, i1 + 1);
						return line.substring(i1, i2);
					} catch (Exception e) {
						return line;
					}
				}

				private void extractPass2Values(Component component) throws IOException {
					String line, token;
					String[] tokens;
					// extract type
					line = findLine(component.name + " = new");
					System.out.println(line);
					if (line != null) {
						token = extractTOken(line, "new ", "(");
						component.type = token;
						System.out.println(token);
					}
					// extract location
					line = findLine(component.name + ".Location =");
					System.out.println(line);
					if (line != null && line.contains("System.Drawing.Point")) {
						token = extractTOken(line, "(", ")");
						tokens = token.split(",");
						component.x = Integer.parseInt(tokens[0].trim());
						component.y = Integer.parseInt(tokens[1].trim());
						System.out.println(token);
					}
					// extract dimension
					line = findLine(component.name + ".Size =");
					System.out.println(line);
					if (line != null && line.contains("System.Drawing.Size")) {
						token = extractTOken(line, "(", ")");
						tokens = token.split(",");
						component.w = Integer.parseInt(tokens[0].trim());
						component.h = Integer.parseInt(tokens[1].trim());
						System.out.println(token);
					}
					// extract label
					line = findLine(component.name + ".Text =");
					if (line != null) {
						token = extractTOken(line, "\"", "\";");
						component.label = token;
						System.out.println(token);
					}
					System.out.println(line);
				}

				private String findLine(String prefix) throws IOException {
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(new ByteArrayInputStream(compilation.initMethodSource.getBytes())));
					String line;
					while ((line = reader.readLine()) != null) {
						line = line.trim();
						if (line.startsWith(prefix)) {
							reader.close();
							return line;
						}
					}
					return null;
				}

			});
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private static String exportHtml5(Compilation compilation) throws IOException {

		final StringBuilder html = new StringBuilder(8544);
		final StringBuilder css = new StringBuilder(8544);
		compilation.visitTree(false, new Compilation.Visitor() {

			@Override
			public void enter(Container container, int depth) throws IOException {
				if (container.name.equals("this")) {
					// html.append("<!DOCTYPE HTML>\n<html><body>");
					html.append("<div id=\"parent\">");
					css.append("#parent {");
					css.append("  position: relative;");
					css.append("  width: 800px; ");
					css.append("  height: 600px; ");
					css.append("  background-color: #fafafa; ");
					css.append("  border: solid 3px #9e70ba; ");
					css.append("  font-size: 24px;  ");
					css.append("  text-align: center;");
					css.append("}");

				} else {
					html.append("                                                ".substring(0, depth * 2)
							+ "<div id=\"" + container.name.substring(5) + "\">" + container.name.substring(5) + "\n");

					css.append("\n#" + container.name.substring(5) + " {");
					css.append("\n  position: absolute;");
					css.append("\n  width: " + container.w + "px; ");
					css.append("\n  height: " + container.h + "px; ");
					css.append("\n  left: " + container.x + "px; ");
					css.append("\n  top: " + container.y + "px; ");
					css.append("\n  background-color: #f0f0f0; ");
					css.append("\n  border: solid 3px #78e382; ");
					css.append("\n  font-size: 24px;  ");
					css.append("\n  text-align: center;");
					css.append("\n}");

				}
			}

			@Override
			public void enter(Component component, int depth) throws IOException {
				html.append("                                                ".substring(0, depth * 2) + "<div id=\""
						+ component.name.substring(5) + "\">" + component.name.substring(5) + "\n");
				css.append("\n#" + component.name.substring(5) + " {");
				css.append("\n  position: absolute;");
				css.append("\n  width: " + component.w + "px; ");
				css.append("\n  height: " + component.h + "px; ");
				css.append("\n  left: " + component.x + "px; ");
				css.append("\n  top: " + component.y + "px; ");
				css.append("\n  background-color: #f0f0f0; ");
				css.append("\n  border: solid 3px #78e382; ");
				css.append("\n  font-size: 24px;  ");
				css.append("\n  text-align: center;");
				css.append("\n}");

			}

			@Override
			public void exit(Container container, int depth) throws IOException {
				if (container.name.equals("this")) {
					html.insert(0,
							"<!DOCTYPE HTML>\n<html><head><style>\n" + css.toString() + "\n</style></head><body>\n");
					html.append("</body></html>");
				} else {
					html.append(
							"                                                ".substring(0, depth * 2) + "</div>\n");
				}
			}

			@Override
			public void exit(Component component, int depth) throws IOException {
				html.append("                                                ".substring(0, depth * 2) + "</div>\n");

			}

		});
		return html.toString();

	}

}

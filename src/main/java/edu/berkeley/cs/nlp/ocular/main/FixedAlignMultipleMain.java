package edu.berkeley.cs.nlp.ocular.main;

import static edu.berkeley.cs.nlp.ocular.data.textreader.Charset.HYPHEN;
import static edu.berkeley.cs.nlp.ocular.data.textreader.Charset.SPACE;
import static edu.berkeley.cs.nlp.ocular.util.Tuple2.Tuple2;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tberg.murphy.arrays.a;
import edu.berkeley.cs.nlp.ocular.data.Document;
import edu.berkeley.cs.nlp.ocular.data.LazyRawImageLoader;
import edu.berkeley.cs.nlp.ocular.data.TextAndLineImagesLoader;
import edu.berkeley.cs.nlp.ocular.eval.Evaluator;
import edu.berkeley.cs.nlp.ocular.eval.Evaluator.EvalSuffStats;
import edu.berkeley.cs.nlp.ocular.font.Font;
import edu.berkeley.cs.nlp.ocular.image.ImageUtils;
import edu.berkeley.cs.nlp.ocular.image.ImageUtils.PixelType;
import edu.berkeley.cs.nlp.ocular.image.Visualizer;
import edu.berkeley.cs.nlp.ocular.lm.BasicCodeSwitchLanguageModel;
import edu.berkeley.cs.nlp.ocular.lm.CodeSwitchLanguageModel;
import edu.berkeley.cs.nlp.ocular.lm.FixedLanguageModel;
import edu.berkeley.cs.nlp.ocular.lm.NgramLanguageModel;
import edu.berkeley.cs.nlp.ocular.lm.SingleLanguageModel;
import edu.berkeley.cs.nlp.ocular.model.CharacterTemplate;
import edu.berkeley.cs.nlp.ocular.model.em.BeamingSemiMarkovDP;
import edu.berkeley.cs.nlp.ocular.model.em.CUDAInnerLoop;
import edu.berkeley.cs.nlp.ocular.model.em.DefaultInnerLoop;
import edu.berkeley.cs.nlp.ocular.model.em.DenseBigramTransitionModel;
import edu.berkeley.cs.nlp.ocular.model.em.EmissionCacheInnerLoop;
import edu.berkeley.cs.nlp.ocular.model.em.JOCLInnerLoop;
import edu.berkeley.cs.nlp.ocular.model.emission.CachingEmissionModel;
import edu.berkeley.cs.nlp.ocular.model.emission.CachingEmissionModelExplicitOffset;
import edu.berkeley.cs.nlp.ocular.model.emission.EmissionModel;
import edu.berkeley.cs.nlp.ocular.model.transition.CharacterNgramTransitionModel;
import edu.berkeley.cs.nlp.ocular.model.transition.CharacterNgramTransitionModelMarkovOffset;
import edu.berkeley.cs.nlp.ocular.model.transition.FixedAlignTransition;
import edu.berkeley.cs.nlp.ocular.model.transition.ForcedAlignmentTransitionModel;
import edu.berkeley.cs.nlp.ocular.model.transition.SparseTransitionModel;
import edu.berkeley.cs.nlp.ocular.model.transition.SparseTransitionModel.TransitionState;
import edu.berkeley.cs.nlp.ocular.util.Tuple2;
import tberg.murphy.fig.Execution;
import tberg.murphy.fig.Option;
import tberg.murphy.fileio.f;
import tberg.murphy.indexer.Indexer;
import tberg.murphy.threading.BetterThreader;

/**
 * @author Shruti Rijhwani
 */
public class FixedAlignMultipleMain implements Runnable {
	
	@Option(gloss = "")
//	public static String inputPath = "/Users/tberg/git/ocular/names.txt";
	public static String inputPath = "/Users/shruti/Documents/HistoricalOcr/ocular/names.txt";
	
	@Option(gloss = "")
	public static String outputRelPath = "";
	
	
	@Option(gloss = "")
	public static String fontPath = "/Users/shruti/Documents/HistoricalOcr/ocular/font/font-init.fontser";
	
	@Option(gloss = "")
	public static String lmDir = "/Users/shruti/Documents/HistoricalOcr/ocular/lm";
	
	@Option(gloss = "")
	public static String lmBaseName = "nyt";
	
	
	@Option(gloss = "")
	public static int paddingMinWidth = 1;
	
	@Option(gloss = "")
	public static int paddingMaxWidth = 5;
	
	
	@Option(gloss = "")
	public static boolean markovVerticalOffset = false;
	
	@Option(gloss = "")
	public static int beamSize = 50;
	
	@Option(gloss = "")
	public static int numEMIters = 4;
	
	
	@Option(gloss = "")
	public static EmissionCacheInnerLoopType emissionEngine = EmissionCacheInnerLoopType.DEFAULT;

	@Option(gloss = "")
	public static int cudaDeviceID = 0;
	
	@Option(gloss = "")
	public static int numMstepThreads = 2;
	
	@Option(gloss = "")
	public static int numEmissionCacheThreads = 2;
	
	@Option(gloss = "")
	public static int numDecodeThreads = 2;

	
	@Option(gloss = "")
	public static boolean popupVisuals = true;
	
	@Option(gloss = "")
	public static boolean writeVisuals = true;

	@Option(gloss = "")
	public static boolean evaluate = true;
	
	
	public static enum EmissionCacheInnerLoopType {DEFAULT, OPENCL, CUDA};

	public static void main(String[] args) {
		FixedAlignMultipleMain main = new FixedAlignMultipleMain();
		Execution.run(args, main);
	}

	public void run() {
		long overallNanoTime = System.nanoTime();
		long overallEmissionCacheNanoTime = 0;
		
		EmissionCacheInnerLoop emissionInnerLoop = null;
		if (emissionEngine == EmissionCacheInnerLoopType.DEFAULT) {
			emissionInnerLoop = new DefaultInnerLoop(numEmissionCacheThreads);
		} else if (emissionEngine == EmissionCacheInnerLoopType.OPENCL) {
			emissionInnerLoop = new JOCLInnerLoop(numEmissionCacheThreads);
		} else if (emissionEngine == EmissionCacheInnerLoopType.CUDA) {
			emissionInnerLoop = new CUDAInnerLoop(numEmissionCacheThreads, cudaDeviceID);
		}
		
		List<Tuple2<String,Map<String,EvalSuffStats>>> allEvals = new ArrayList<Tuple2<String,Map<String,EvalSuffStats>>>();
		
		String lmFileName = "test.txt";
		
//		List<Document> documents = TextAndLineImagesLoader.loadDocuments(inputPath, CharacterTemplate.LINE_HEIGHT);
	
		List<String> paths = new ArrayList<String>();
		paths.add("16.jpg");
//		paths.add("16.jpg");
//		paths.add("3.png");
//		paths.add("4.png");
		
		List<Document> documents = LazyRawImageLoader.loadDocuments(paths, null, Integer.MAX_VALUE, 0, true, 0.12, false);
		
		if (documents.isEmpty()) throw new NoDocumentsFoundException();
		
		System.out.println("Loading LM..");
		
		final FixedLanguageModel lm = new FixedLanguageModel(lmFileName);
		final Indexer<String> charIndexer = lm.getCharacterIndexer();
		
		System.out.println("Loading font initializer..");
		Font font = InitializeFont.readFont(fontPath);
		final CharacterTemplate[] templates = new CharacterTemplate[charIndexer.size()];
		for (int c=0; c<templates.length; ++c) {
			templates[c] = font.get(charIndexer.getObject(c));
		}
		
		System.out.println("Characters: " + charIndexer.getObjects());
		System.out.println("Num characters: " + charIndexer.size());			

		final BasicCodeSwitchLanguageModel lm2 = LMTrainMain.readLM(lmDir+"/"+lmBaseName+".lmser");
		

		for (int iter=0; iter<numEMIters; ++iter) {
			
			List<TransitionState[][]> nDecodeStates = new ArrayList<TransitionState[][]>();
			
			for (Document doc : documents) {
				
				final PixelType[][][] pixels = doc.loadLineImages();
				final String[][] text = doc.loadDiplomaticTextLines();
	
				final EmissionModel emissionModel = (markovVerticalOffset ? new CachingEmissionModelExplicitOffset(templates, charIndexer, pixels, paddingMinWidth, paddingMaxWidth, emissionInnerLoop) : new CachingEmissionModel(templates, charIndexer, pixels, paddingMinWidth, paddingMaxWidth, emissionInnerLoop));
								
				SparseTransitionModel forwardTransitionModel = null;
				
				forwardTransitionModel = new FixedAlignTransition(lm);
				
				DenseBigramTransitionModel nullBackwardTransitionModel = new DenseBigramTransitionModel(lm2);
				
				long emissionCacheNanoTime = System.nanoTime();
				emissionModel.rebuildCache();
				overallEmissionCacheNanoTime += (System.nanoTime() - emissionCacheNanoTime);
			
				// e-step
				System.out.println("Iteration "+iter+" e-step");
				double logJointProb = Double.NEGATIVE_INFINITY;
				long nanoTime = System.nanoTime();
				BeamingSemiMarkovDP dp = new BeamingSemiMarkovDP(emissionModel, forwardTransitionModel, nullBackwardTransitionModel);
				Tuple2<Tuple2<TransitionState[][],int[][]>,Double> decodeStatesAndWidthsAndJointLogProb = dp.decode(beamSize, numDecodeThreads);
				logJointProb = decodeStatesAndWidthsAndJointLogProb._2;
				final TransitionState[][] decodeStates = decodeStatesAndWidthsAndJointLogProb._1._1;
				final int[][] decodeWidths = decodeStatesAndWidthsAndJointLogProb._1._2;
				System.out.println("Compute marginals and decode: " + (System.nanoTime() - nanoTime)/1000000 + "ms");
				System.gc();
				System.gc();
				System.gc();
				System.out.println("Iteration "+iter+": "+logJointProb);

				// visualize
				printTranscription(iter, doc, allEvals, pixels, text, decodeStates, decodeWidths, charIndexer, templates, emissionModel);
			
				if (iter < numEMIters-1) {
					// m-step
					nanoTime = System.nanoTime();
					{												
						nDecodeStates.add(decodeStates);
						
						for (int c=0; c<templates.length; ++c) if (templates[c] != null) templates[c].clearCounts();
						BetterThreader.Function<Integer,Object> func1 = new BetterThreader.Function<Integer,Object>(){public void call(Integer line, Object ignore){
							emissionModel.incrementCounts(line, decodeStates[line], decodeWidths[line]);
						}};
						BetterThreader<Integer,Object> threader1 = new BetterThreader<Integer,Object>(func1, numMstepThreads);
						for (int line=0; line<emissionModel.numSequences(); ++line) threader1.addFunctionArgument(line);
						threader1.run();
						BetterThreader.Function<Integer,Object> func2 = new BetterThreader.Function<Integer,Object>(){public void call(Integer c, Object ignore){
							if (templates[c] != null) templates[c].updateParameters();
						}};
						BetterThreader<Integer,Object> threader2 = new BetterThreader<Integer,Object>(func2, numMstepThreads);
						for (int c=0; c<templates.length; ++c) threader2.addFunctionArgument(c);
						threader2.run();
					}
					System.out.println("Update parameters: " + (System.nanoTime() - nanoTime)/1000000 + "ms");
					emissionCacheNanoTime = System.nanoTime();
					emissionModel.rebuildCache();
					overallEmissionCacheNanoTime += (System.nanoTime() - emissionCacheNanoTime);
				}
			}

			if (nDecodeStates.size() > 0) {
				lm.updateProbs(nDecodeStates);
			}
		}
		
		if (!allEvals.isEmpty() && evaluate) {
			printEvaluation(allEvals);
		}
		
		System.out.println("Emission cache time: " + overallEmissionCacheNanoTime/1e9 + "s");
		System.out.println("Overall time: " + (System.nanoTime() - overallNanoTime)/1e9 + "s");
	}
	
	public static void printEvaluation(List<Tuple2<String,Map<String,EvalSuffStats>>> allEvals) {
		Map<String,EvalSuffStats> totalSuffStats = new HashMap<String,EvalSuffStats>();
		StringBuffer buf = new StringBuffer();
		buf.append("All evals:\n");
		for (Tuple2<String,Map<String,EvalSuffStats>> docNameAndEvals : allEvals) {
			String docName = docNameAndEvals._1;
			Map<String,EvalSuffStats> evals = docNameAndEvals._2;
			buf.append("Document: "+docName+"\n");
			buf.append(Evaluator.renderEval(evals)+"\n");
			for (String evalType : evals.keySet()) {
				EvalSuffStats eval = evals.get(evalType);
				EvalSuffStats totalEval = totalSuffStats.get(evalType);
				if (totalEval == null) {
					totalEval = new EvalSuffStats();
					totalSuffStats.put(evalType, totalEval);
				}
				totalEval.increment(eval);
			}
		}
		
		buf.append("\nMarco-avg total eval:\n");
		buf.append(Evaluator.renderEval(totalSuffStats)+"\n");
		
		f.writeString(Execution.getVirtualExecDir()+"/"+outputRelPath+"/out.txt", buf.toString());
		System.out.println();
		System.out.println(buf.toString());
	}
	
	private static String printTranscription(int iter, Document doc, List<Tuple2<String,Map<String,EvalSuffStats>>> allEvals, PixelType[][][] pixels, String[][] text, TransitionState[][] decodeStates, int[][] decodeWidths, Indexer<String> charIndexer, CharacterTemplate[] templates, EmissionModel emissionModel) {
		StringBuffer guessOut = new StringBuffer();
		if (evaluate || writeVisuals || popupVisuals) {
			@SuppressWarnings("unchecked")
			List<Integer>[] segmentBoundaries = new List[pixels.length];
			@SuppressWarnings("unchecked")
			List<String>[] viterbiChars = new List[pixels.length];
			@SuppressWarnings("unchecked")
			List<double[]>[] pixelsBlackProbsLists = new List[pixels.length];
			for (int d=0; d<decodeStates.length; ++d) {
				segmentBoundaries[d] = new ArrayList<Integer>();
				viterbiChars[d] = new ArrayList<String>();
				pixelsBlackProbsLists[d] = new ArrayList<double[]>();

				int t = 0;
				for (int di=0; di<decodeStates[d].length; ++di) {
					int c = decodeStates[d][di].getGlyphChar().templateCharIndex;
					int w = decodeWidths[d][di];
					int e = emissionModel.getExposure(d, t, decodeStates[d][di], w);
					int offset = emissionModel.getOffset(d, t, decodeStates[d][di], w);
					int pw = emissionModel.getPadWidth(d, t, decodeStates[d][di], w);

					if (c == charIndexer.getIndex(SPACE)) {
						for (int ti=t; ti<t+(w-pw); ++ti) {
							segmentBoundaries[d].add(ti);
						}
					}
					for (int ti=t+(w-pw); ti<t+w; ++ti) {
						segmentBoundaries[d].add(ti);
					}

					if (viterbiChars[d].isEmpty() || !(HYPHEN.equals(viterbiChars[d].get(viterbiChars[d].size()-1)) && HYPHEN.equals(charIndexer.getObject(c)))) {
						viterbiChars[d].add(charIndexer.getObject(c));
					}

					double[][] templateBlackProbs = a.toDouble(templates[c].blackProbs(e, offset, w-pw));
					for (int i=0; i<templateBlackProbs.length; ++i) {
						pixelsBlackProbsLists[d].add(templateBlackProbs[i]);
					}
					double[][] padBlackProbs = a.toDouble(templates[charIndexer.getIndex(SPACE)].blackProbs(e, offset, pw));
					for (int i=0; i<padBlackProbs.length; ++i) {
						pixelsBlackProbsLists[d].add(padBlackProbs[i]);
					}

					t += w;
				}
			}
			double[][][] pixelsBlackProbs = new double[pixels.length][][];
			for (int d=0; d<decodeStates.length; ++d) {
				pixelsBlackProbs[d] = new double[pixelsBlackProbsLists[d].size()][];
				for (int i=0; i<pixelsBlackProbsLists[d].size(); ++i) {
					pixelsBlackProbs[d][i] = pixelsBlackProbsLists[d].get(i);
				}
			}

			List<double[]> alphabetBlackProbsList = new ArrayList<double[]>();
			for (int c=0; c<charIndexer.size(); ++c) {
				if (c != charIndexer.getIndex(SPACE)) {
					int bestWidth = -1;
					double bestProb = Double.NEGATIVE_INFINITY;
					for (int width=templates[c].templateMinWidth(); width<=templates[c].templateMaxWidth(); ++width) {
						double logProb = templates[c].widthLogProb(width);
						if (logProb >= bestProb) {
							bestProb = logProb;
							bestWidth = width;
						}
					}
					double[][] charBlackProbs = a.toDouble(templates[c].blackProbs(0, 0, bestWidth));
					for (double[] col : charBlackProbs) alphabetBlackProbsList.add(col);
					for (int i=0; i<5; ++i) alphabetBlackProbsList.add(new double[charBlackProbs[0].length]);
				}
			}
			double[][][] alphabetBlackProbs = new double[1][alphabetBlackProbsList.size()][];
			for (int i=0; i<alphabetBlackProbsList.size(); ++i) alphabetBlackProbs[0][i] = alphabetBlackProbsList.get(i);
			

			if (text != null && evaluate) {
				@SuppressWarnings("unchecked")
				List<String>[] goldCharSequences = new List[text.length];
				for (int d=0; d<text.length; ++d) {
					goldCharSequences[d] = new ArrayList<String>();
					for (int i=0; i<text[d].length; ++i) {
						goldCharSequences[d].add(text[d][i]);
					}
				}

				StringBuffer guessAndGoldOut = new StringBuffer();
				for (int d=0; d<viterbiChars.length; ++d) {
					for (String c : viterbiChars[d]) {
						guessAndGoldOut.append(c);
						guessOut.append(c);
					}
					guessAndGoldOut.append("\n");
					for (String c : goldCharSequences[d]) {
						guessAndGoldOut.append(c);
					}
					guessAndGoldOut.append("\n");
					guessAndGoldOut.append("\n");

					guessOut.append("\n");
				}

				Map<String,EvalSuffStats> evals = Evaluator.getUnsegmentedEval(viterbiChars, goldCharSequences, true);
				if (iter == FixedAlignMultipleMain.numEMIters-1) {
					allEvals.add(Tuple2(doc.baseName(), evals));
				}
				System.out.println(guessAndGoldOut.toString()+Evaluator.renderEval(evals));

				File outputDir = new File(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName());
				outputDir.mkdirs();
				f.writeString(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName()+"/out-"+iter+".txt", guessAndGoldOut.toString()+Evaluator.renderEval(evals));
			} else {
				for (int d=0; d<viterbiChars.length; ++d) {
					for (String c : viterbiChars[d]) {
						guessOut.append(c);
					}
					guessOut.append("\n");
					guessOut.append("\n");
				}
				System.out.println(guessOut.toString());
				
				File outputDir = new File(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName());
				outputDir.mkdirs();
				f.writeString(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName()+"/out-"+iter+".txt", guessOut.toString());
			}
			if (writeVisuals) {
				File outputDir = new File(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName());
				outputDir.mkdirs();
				f.writeImage(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName()+"/overlay-"+iter+".png", Visualizer.renderOverlay(pixels, pixelsBlackProbs, segmentBoundaries));
				f.writeImage(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName()+"/alphabet-"+iter+".png", Visualizer.renderBlackProbs(alphabetBlackProbs));
				f.writeImage(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName()+"/original-"+iter+".png", Visualizer.renderObservations(pixels));
				f.writeImage(Execution.getVirtualExecDir()+"/"+outputRelPath+"/"+doc.baseName()+"/probs-"+iter+".png", Visualizer.renderBlackProbsAndSegmentation(pixelsBlackProbs, segmentBoundaries));
			}
			if (popupVisuals) {
				ImageUtils.display(Visualizer.renderOverlay(pixels, pixelsBlackProbs, segmentBoundaries));
				ImageUtils.display(Visualizer.renderBlackProbs(alphabetBlackProbs));
				ImageUtils.display(Visualizer.renderObservations(pixels));
				ImageUtils.display(Visualizer.renderBlackProbsAndSegmentation(pixelsBlackProbs, segmentBoundaries));
			}
		}
		return guessOut.toString();
	}

}
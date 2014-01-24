/* Copyright (C) 2013 TU Dortmund
 * This file is part of LearnLib, http://www.learnlib.de/.
 * 
 * LearnLib is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License version 3.0 as published by the Free Software Foundation.
 * 
 * LearnLib is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with LearnLib; if not, see
 * <http://www.gnu.de/documents/lgpl.en.html>.
 */
package de.learnlib.algorithms.baselinelstar;

import com.google.common.collect.Maps;
import de.learnlib.algorithms.features.GlobalSuffixLearner.GlobalSuffixLearnerDFA;
import de.learnlib.algorithms.features.observationtable.OTLearner;
import de.learnlib.api.MembershipOracle;
import de.learnlib.oracles.DefaultQuery;
import net.automatalib.automata.fsa.DFA;
import net.automatalib.automata.fsa.impl.FastDFA;
import net.automatalib.automata.fsa.impl.FastDFAState;
import net.automatalib.words.Alphabet;
import net.automatalib.words.Word;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of the L* algorithm by Dana Angluin
 *
 * @param <I>
 * 		input symbol class.
 */
public class BaselineLStar<I> implements OTLearner<DFA<?, I>, I, Boolean>, GlobalSuffixLearnerDFA<I> {

	private final Alphabet<I> alphabet;

	private final MembershipOracle<I, Boolean> oracle;

	private ObservationTable<I, Boolean> observationTable;

	private boolean startLearningAlreadyCalled;

	/**
	 * Initializes a newly created baseline L* implementation. After this, the method
	 * {@link #startLearning()} may be called once.
	 *
	 * @param alphabet
	 * 		The {@link Alphabet} to learn.
	 * @param oracle
	 * 		The {@link MembershipOracle} which is used for membership queries.
	 */
	public BaselineLStar(Alphabet<I> alphabet, MembershipOracle<I, Boolean> oracle) {
		this.alphabet = alphabet;
		this.oracle = oracle;
		this.observationTable = new ObservationTable<>();

		for (I alphabetSymbol : alphabet) {
			observationTable.addLongPrefix(Word.fromLetter(alphabetSymbol));
		}
	}

	@Override
	public void startLearning() {
		if (startLearningAlreadyCalled) {
			throw new IllegalStateException("startLearning may only be called once!");
		}

		final List<Word<I>> allSuffixes = observationTable.getSuffixes();

		processMembershipQueriesForStates(observationTable.getShortPrefixLabels(), allSuffixes);
		processMembershipQueriesForStates(observationTable.getLongPrefixLabels(), allSuffixes);

		makeTableClosedAndConsistent();

		startLearningAlreadyCalled = true;
	}

	@Override
	public boolean refineHypothesis(DefaultQuery<I, Boolean> ceQuery) {
		if (!startLearningAlreadyCalled) {
			throw new IllegalStateException("Unable to refine hypothesis before first learn iteration!");
		}

		LinkedHashSet<Word<I>> prefixes = prefixesOfWordNotInStates(ceQuery.getInput());

		for (Word<I> prefix : prefixes) {
			observationTable.addShortPrefix(prefix);
		}

		observationTable.removeShortPrefixesFromLongPrefixes();

		LinkedHashSet<Word<I>> newCandidates = getNewCandidatesFromPrefixes(prefixes);

		for (Word<I> candidate : newCandidates) {
			observationTable.addLongPrefix(candidate);
		}

		processMembershipQueriesForStates(prefixes, observationTable.getSuffixes());
		processMembershipQueriesForStates(newCandidates, observationTable.getSuffixes());

		makeTableClosedAndConsistent();

		return true;
	}

	private LinkedHashSet<Word<I>> prefixesOfWordNotInStates(Word<I> word) {
		List<Word<I>> states = observationTable.getShortPrefixLabels();

		LinkedHashSet<Word<I>> prefixes = new LinkedHashSet<>();
		for (Word<I> prefix : word.prefixes(false)) {
			if (!states.contains(prefix)) {
				prefixes.add(prefix);
			}
		}

		return prefixes;
	}

	private LinkedHashSet<Word<I>> getNewCandidatesFromPrefixes(LinkedHashSet<Word<I>> prefixes) {
		LinkedHashSet<Word<I>> newCandidates = new LinkedHashSet<>();

		for (Word<I> prefix : prefixes) {
			Set<Word<I>> possibleCandidates = appendAlphabetSymbolsToWord(prefix);
			for (Word<I> possibleCandidate :possibleCandidates) {
				if (!observationTable.getShortPrefixLabels().contains(possibleCandidate)) {
					newCandidates.add(possibleCandidate);
				}
			}
		}

		return newCandidates;
	}

	/**
	 * Appends each symbol of the alphabet (with size m) to the given word (with size w),
	 * thus returning m words with a length of w+1.
	 *
	 * @param word
	 *      The {@link Word} to which the {@link Alphabet} is appended.
	 * @return
	 *      A set with the size of the alphabet, containing each time the word
	 *      appended with an alphabet symbol.
	 */
	private LinkedHashSet<Word<I>> appendAlphabetSymbolsToWord(Word<I> word) {
		LinkedHashSet<Word<I>> newCandidates = new LinkedHashSet<>(alphabet.size());
		for (I alphabetSymbol : alphabet) {
			Word<I> newCandidate = word.append(alphabetSymbol);
			newCandidates.add(newCandidate);
		}
		return newCandidates;
	}

	@Override
	public DFA<?, I> getHypothesisModel() {
		if (!startLearningAlreadyCalled) {
			throw new IllegalStateException("Unable to get hypothesis model before first learn iteration!");
		}

		FastDFA<I> automaton = new FastDFA<>(alphabet);
		Map<List<Boolean>, FastDFAState> dfaStates = Maps.newHashMapWithExpectedSize(
				observationTable.getShortPrefixRows().size());

		for (ObservationTableRow<I, Boolean> stateRow : observationTable.getShortPrefixRows()) {
			if (dfaStates.containsKey(stateRow.getValues())) {
				continue;
			}

			FastDFAState dfaState;

			if (stateRow.getLabel().isEmpty()) {
				dfaState = automaton.addInitialState();
			}
			else {
				dfaState = automaton.addState();
			}

			Word<I> emptyWord = Word.epsilon();
			int positionOfEmptyWord = observationTable.getSuffixes().indexOf(emptyWord);
			dfaState.setAccepting(stateRow.getValues().get(positionOfEmptyWord));
			dfaStates.put(stateRow.getValues(), dfaState);
		}

		for (ObservationTableRow<I, Boolean> stateRow : observationTable.getShortPrefixRows()) {
			FastDFAState dfaState = dfaStates.get(stateRow.getValues());
			for (I alphabetSymbol : alphabet) {
				Word<I> word = stateRow.getLabel().append(alphabetSymbol);

				final int index = alphabet.getSymbolIndex(alphabetSymbol);
				dfaState.setTransition(index, dfaStates.get(observationTable.getRowForPrefix(word).getValues()));
			}
		}

		return automaton;
	}

	/**
	 * After calling this method the observation table is both closed and consistent.
	 */
	private void makeTableClosedAndConsistent() {
		boolean closedAndConsistent = false;

		while (!closedAndConsistent) {
			closedAndConsistent = true;

			if (!observationTable.isClosed()) {
				closedAndConsistent = false;
				closeTable();
			}

			if (!observationTable.isConsistentWithAlphabet(alphabet)) {
				closedAndConsistent = false;
				ensureConsistency();
			}
		}
	}

	/**
	 * After calling this method the observation table is closed.
	 */
	private void closeTable() {
		Word<I> candidate = observationTable.findUnclosedState();

		while (candidate != null) {
			observationTable.moveLongPrefixToShortPrefixes(candidate);

			LinkedHashSet<Word<I>> newCandidates = appendAlphabetSymbolsToWord(candidate);
			for (Word<I> newCandidate : newCandidates) {
				observationTable.addLongPrefix(newCandidate);
			}

			processMembershipQueriesForStates(newCandidates, observationTable.getSuffixes());

			candidate = observationTable.findUnclosedState();
		}
	}

	/**
	 * After calling this method the observation table is consistent.
	 */
	private void ensureConsistency() {
		InconsistencyDataHolder<I> dataHolder = observationTable.findInconsistentSymbol(alphabet);

		Word<I> witness = observationTable.determineWitnessForInconsistency(dataHolder);
		Word<I> newSuffix = Word.fromSymbols(dataHolder.getDifferingSymbol()).concat(witness);
		observationTable.addSuffix(newSuffix);

		List<Word<I>> singleSuffixList = Collections.singletonList(newSuffix);

		processMembershipQueriesForStates(observationTable.getShortPrefixLabels(), singleSuffixList);
		processMembershipQueriesForStates(observationTable.getLongPrefixLabels(), singleSuffixList);
	}

	/**
	 * When new states are added to the observation table, this method fills the table values. For each
	 * given state it sends one membership query for each specified suffix symbol to the oracle of the
	 * form (state,symbol).
	 *
	 * @param states
	 * 		The new states which should be evaluated.
	 * @param suffixes
	 * 		The suffixes which are appended to the states before sending the resulting word to the oracle.
	 */
	private void processMembershipQueriesForStates(Collection<Word<I>> states, Collection<? extends Word<I>> suffixes) {
		List<DefaultQuery<I, Boolean>> queries = new ArrayList<>(states.size());
		for (Word<I> label : states) {
			for (Word<I> suffix : suffixes) {
				queries.add(new DefaultQuery<I, Boolean>(label, suffix));
			}
		}

		oracle.processQueries(queries);

		
		for(DefaultQuery<I,Boolean> query : queries) {
			Word<I> state = query.getPrefix();
			Word<I> suffix = query.getSuffix();
			observationTable.addResult(state, suffix, query.getOutput());
		}
	}

	public String getStringRepresentationOfObservationTable() {
		return ObservationTablePrinter.getPrintableStringRepresentation(observationTable);
	}

	@Override
	public Collection<? extends Word<I>> getGlobalSuffixes() {
		return Collections.unmodifiableCollection(observationTable.getSuffixes());
	}

	@Override
	public boolean addGlobalSuffixes(
			Collection<? extends Word<I>> newGlobalSuffixes) {
		observationTable.getSuffixes().addAll(newGlobalSuffixes);

		int numStatesOld = observationTable.getShortPrefixRows().size();

		processMembershipQueriesForStates(observationTable.getShortPrefixLabels(), newGlobalSuffixes);
		processMembershipQueriesForStates(observationTable.getLongPrefixLabels(), newGlobalSuffixes);

		closeTable();

		return (observationTable.getShortPrefixRows().size() != numStatesOld);
	}

	@Override
	public de.learnlib.algorithms.features.observationtable.ObservationTable<I, Boolean> getObservationTable() {
		return observationTable;
	}
}

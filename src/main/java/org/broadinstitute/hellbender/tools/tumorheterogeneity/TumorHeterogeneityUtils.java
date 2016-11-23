package org.broadinstitute.hellbender.tools.tumorheterogeneity;

import autovalue.shaded.com.google.common.common.collect.Sets;
import org.apache.commons.math3.analysis.function.Logit;
import org.apache.commons.math3.analysis.function.Sigmoid;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.special.Gamma;
import org.broadinstitute.hellbender.tools.tumorheterogeneity.ploidystate.PloidyState;
import org.broadinstitute.hellbender.tools.tumorheterogeneity.ploidystate.PloidyStatePrior;
import org.broadinstitute.hellbender.utils.GATKProtectedMathUtils;
import org.broadinstitute.hellbender.utils.MathUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
final class TumorHeterogeneityUtils {
    private static final double EPSILON = 1E-10;

    private TumorHeterogeneityUtils() {}

    static double calculateLogPosterior(final TumorHeterogeneityState state,
                                        final TumorHeterogeneityData data) {
        final int numPopulations = state.populationMixture().numPopulations();
        final int numSegments = data.numSegments();

        //concentration prior
        final double concentrationPriorAlpha = state.priors().concentrationPriorHyperparameterValues().getAlpha();
        final double concentrationPriorBeta = state.priors().concentrationPriorHyperparameterValues().getBeta();
        final double concentration = state.concentration();
        final double logPriorConcentration =
                concentrationPriorAlpha * Math.log(concentrationPriorBeta + EPSILON)
                        + (concentrationPriorAlpha - 1.) * Math.log(concentration + EPSILON)
                        - concentrationPriorBeta * concentration
                        - Gamma.logGamma(concentrationPriorAlpha);

        //copy-ratio noise-floor prior
        final double copyRatioNoiseFloorPriorAlpha = state.priors().copyRatioNoiseFloorPriorHyperparameterValues().getAlpha();
        final double copyRatioNoiseFloorPriorBeta = state.priors().copyRatioNoiseFloorPriorHyperparameterValues().getBeta();
        final double copyRatioNoiseFloor = state.copyRatioNoiseFloor();
        final double logPriorCopyRatioNoiseFloor =
                copyRatioNoiseFloorPriorAlpha * Math.log(copyRatioNoiseFloorPriorBeta + EPSILON)
                        + (copyRatioNoiseFloorPriorAlpha - 1.) * Math.log(copyRatioNoiseFloor + EPSILON)
                        - copyRatioNoiseFloorPriorBeta * copyRatioNoiseFloor
                        - Gamma.logGamma(copyRatioNoiseFloorPriorAlpha);

        //copy-ratio noise-factor prior
        final double copyRatioNoiseFactorPriorAlpha = state.priors().copyRatioNoiseFactorPriorHyperparameterValues().getAlpha();
        final double copyRatioNoiseFactorPriorBeta = state.priors().copyRatioNoiseFactorPriorHyperparameterValues().getBeta();
        final double copyRatioNoiseFactor = state.copyRatioNoiseFactor();
        final double logPriorCopyRatioNoiseFactor =
                copyRatioNoiseFactorPriorAlpha * Math.log(copyRatioNoiseFactorPriorBeta + EPSILON)
                        + (copyRatioNoiseFactorPriorAlpha - 1.) * Math.log(copyRatioNoiseFactor - 1. + EPSILON)
                        - copyRatioNoiseFactorPriorBeta * (copyRatioNoiseFactor - 1.)
                        - Gamma.logGamma(copyRatioNoiseFactorPriorAlpha);

        //minor-allele-fraction noise-factor prior
        final double minorAlleleFractionNoiseFactorPriorAlpha = state.priors().minorAlleleFractionNoiseFactorPriorHyperparameterValues().getAlpha();
        final double minorAlleleFractionNoiseFactorPriorBeta = state.priors().minorAlleleFractionNoiseFactorPriorHyperparameterValues().getBeta();
        final double minorAlleleFractionNoiseFactor = state.minorAlleleFractionNoiseFactor();
        final double logPriorMinorAlleleFractionNoiseFactor =
                minorAlleleFractionNoiseFactorPriorAlpha * Math.log(minorAlleleFractionNoiseFactorPriorBeta + EPSILON)
                        + (minorAlleleFractionNoiseFactorPriorAlpha - 1.) * Math.log(minorAlleleFractionNoiseFactor - 1. + EPSILON)
                        - minorAlleleFractionNoiseFactorPriorBeta * (minorAlleleFractionNoiseFactor - 1.)
                        - Gamma.logGamma(minorAlleleFractionNoiseFactorPriorAlpha);

        //population-fractions prior
        final double logPriorPopulationFractionsSum = IntStream.range(0, numPopulations)
                .mapToDouble(i -> (concentration - 1.) * Math.log(state.populationMixture().populationFraction(i) + EPSILON))
                .sum();
        final double logPriorPopulationFractions =
                Gamma.logGamma(concentration * numPopulations)
                        - numPopulations * Gamma.logGamma(concentration)
                        + logPriorPopulationFractionsSum;

        //variant-profiles prior
        final double logPriorVariantProfiles = calculateLogPriorVariantProfiles(state.populationMixture().variantProfileCollection(), state.priors().ploidyStatePrior());

        //copy-ratio--minor-allele-fraction likelihood
        double logLikelihoodSegments = 0.;
        final double ploidy = state.populationMixture().ploidy(data);
        for (int segmentIndex = 0; segmentIndex < numSegments; segmentIndex++) {
            final double totalCopyNumber = state.populationMixture().calculatePopulationAveragedCopyNumberFunction(segmentIndex, PloidyState::total);
            final double mAlleleCopyNumber = state.populationMixture().calculatePopulationAveragedCopyNumberFunction(segmentIndex, PloidyState::m);
            final double nAlleleCopyNumber = state.populationMixture().calculatePopulationAveragedCopyNumberFunction(segmentIndex, PloidyState::n);
            final double copyRatio = totalCopyNumber / (ploidy + EPSILON);
            final double minorAlleleFraction = calculateMinorAlleleFraction(mAlleleCopyNumber, nAlleleCopyNumber);
            logLikelihoodSegments += data.logDensity(segmentIndex, copyRatio, minorAlleleFraction, copyRatioNoiseFloor, copyRatioNoiseFactor, minorAlleleFractionNoiseFactor);
        }

        return logPriorConcentration + logPriorCopyRatioNoiseFloor + logPriorCopyRatioNoiseFactor + logPriorMinorAlleleFractionNoiseFactor +
                logPriorPopulationFractions + logPriorVariantProfiles + logLikelihoodSegments;
    }

    private static double calculateLogPriorVariantProfiles(final PopulationMixture.VariantProfileCollection variantProfileCollection,
                                                           final PloidyStatePrior ploidyStatePrior) {
        double logPriorVariantProfiles = 0.;
        for (int populationIndex = 0; populationIndex < variantProfileCollection.numVariantPopulations(); populationIndex++) {
            for (int segmentIndex = 0; segmentIndex < variantProfileCollection.numSegments(); segmentIndex++) {
                final PloidyState ploidyState = variantProfileCollection.ploidyState(populationIndex, segmentIndex);
                logPriorVariantProfiles += ploidyStatePrior.logProbability(ploidyState);
            }
        }
        return logPriorVariantProfiles;
    }

    private static double calculateMinorAlleleFraction(final double m, final double n) {
        return Math.min(m, n) / (m + n + EPSILON);
    }

    static PopulationMixture.VariantProfileCollection proposeVariantProfileCollection(final RandomGenerator rng,
                                                                                      final TumorHeterogeneityState currentState,
                                                                                      final TumorHeterogeneityData data,
                                                                                      final PopulationMixture.PopulationFractions proposedPopulationFractions,
                                                                                      final int maxTotalCopyNumber,
                                                                                      final List<List<Integer>> totalCopyNumberProductStates,
                                                                                      final Map<Integer, Set<PloidyState>> ploidyStateSetsMap) {
        final int numPopulations = currentState.populationMixture().numPopulations();
        final int numSegments = data.numSegments();
        final PloidyState normalPloidyState = currentState.priors().normalPloidyState();
        final List<PopulationMixture.VariantProfile> variantProfiles = new ArrayList<>(Collections.nCopies(numPopulations - 1,
                new PopulationMixture.VariantProfile(Collections.nCopies(numSegments, normalPloidyState))));

        final double currentPloidy = currentState.populationMixture().ploidy(data);
        TumorHeterogeneitySamplers.logger.info("Current population fractions: " + currentState.populationMixture().populationFractions());
        TumorHeterogeneitySamplers.logger.info("Current ploidy: " + currentPloidy);

        final double proposedPloidy = proposePloidy(rng, currentPloidy, maxTotalCopyNumber);
        TumorHeterogeneitySamplers.logger.info("Proposed initial ploidy: " + proposedPloidy);

        for (int segmentIndex = 0; segmentIndex < numSegments; segmentIndex++) {
            final int si = segmentIndex;
            final double[] log10ProbabilitiesCopyRatio = totalCopyNumberProductStates.stream()
                    .mapToDouble(tcnps -> calculateTotalCopyNumber(proposedPopulationFractions, tcnps, normalPloidyState) / proposedPloidy)
                    .map(cr -> data.copyRatioLogDensity(si, cr, currentState.copyRatioNoiseFloor(), currentState.copyRatioNoiseFactor()))
                    .map(MathUtils::logToLog10)
                    .toArray();
            final double[] probabilitiesCopyRatio = MathUtils.normalizeFromLog10ToLinearSpace(log10ProbabilitiesCopyRatio);
            final Function<List<Integer>, Double> probabilityFunctionCopyRatio = totalCopyNumberProductState ->
                    probabilitiesCopyRatio[totalCopyNumberProductStates.indexOf(totalCopyNumberProductState)];
            final List<Integer> totalCopyNumberProductState = GATKProtectedMathUtils.randomSelect(totalCopyNumberProductStates, probabilityFunctionCopyRatio, rng);
            final double totalCopyRatio = calculateTotalCopyNumber(proposedPopulationFractions, totalCopyNumberProductState, normalPloidyState) / proposedPloidy;

            final List<List<PloidyState>> ploidyStateProductStates =
                    new ArrayList<>(Sets.cartesianProduct(totalCopyNumberProductState.stream().map(ploidyStateSetsMap::get).collect(Collectors.toList())));
            final double[] log10Probabilities = ploidyStateProductStates.stream()
                    .mapToDouble(ps -> calculateMinorAlleleFraction(proposedPopulationFractions, ps, normalPloidyState))
                    .map(maf -> data.logDensity(si, totalCopyRatio, maf, currentState.copyRatioNoiseFloor(), currentState.copyRatioNoiseFactor(), currentState.minorAlleleFractionNoiseFactor()))
                    .map(MathUtils::logToLog10)
                    .toArray();
            final double[] probabilities = MathUtils.normalizeFromLog10ToLinearSpace(log10Probabilities);
            final Function<List<PloidyState>, Double> probabilityFunction = ploidyStateProductState ->
                    probabilities[ploidyStateProductStates.indexOf(ploidyStateProductState)];
            final List<PloidyState> ploidyStateProductState = GATKProtectedMathUtils.randomSelect(ploidyStateProductStates, probabilityFunction, rng);

            IntStream.range(0, numPopulations - 1).forEach(i -> variantProfiles.get(i).set(si, ploidyStateProductState.get(i)));
        }
        return new PopulationMixture.VariantProfileCollection(variantProfiles);
    }

    private static double calculateTotalCopyNumber(final PopulationMixture.PopulationFractions populationFractions,
                                               final List<Integer> totalCopyNumberProductState,
                                               final PloidyState normalPloidyState) {
        final int numPopulations = populationFractions.size();
        return IntStream.range(0, numPopulations - 1).boxed()
                .mapToDouble(i -> totalCopyNumberProductState.get(i) * populationFractions.get(i))
                .sum() + normalPloidyState.total() * populationFractions.get(numPopulations - 1);
    }

    private static double calculateMinorAlleleFraction(final PopulationMixture.PopulationFractions populationFractions,
                                                   final List<PloidyState> ploidyStateProductState,
                                                   final PloidyState normalPloidyState) {
        final int numPopulations = populationFractions.size();
        final double mAlleleCopyNumber = IntStream.range(0, numPopulations - 1).boxed()
                .mapToDouble(i -> ploidyStateProductState.get(i).m() * populationFractions.get(i))
                .sum() + normalPloidyState.m() * populationFractions.get(numPopulations - 1);
        final double nAlleleCopyNumber = IntStream.range(0, numPopulations - 1).boxed()
                .mapToDouble(i -> ploidyStateProductState.get(i).n() * populationFractions.get(i))
                .sum() + normalPloidyState.n() * populationFractions.get(numPopulations - 1);
        return Math.min(mAlleleCopyNumber, nAlleleCopyNumber) / (mAlleleCopyNumber + nAlleleCopyNumber + TumorHeterogeneitySamplers.EPSILON);
    }

    static double calculateLogJacobianFactor(final PopulationMixture.PopulationFractions populationFractions) {
        final List<Double> breakProportions = calculateBreakProportionsFromPopulationFractions(populationFractions);
        return IntStream.range(0, populationFractions.size() - 1).boxed()
                .mapToDouble(i -> Math.log(populationFractions.get(i)) + Math.log(1. - breakProportions.get(i))).sum();

    }

    static List<Double> calculateTransformedPopulationFractionsFromPopulationFractions(final PopulationMixture.PopulationFractions populationFractions) {
        final List<Double> breakProportions = calculateBreakProportionsFromPopulationFractions(populationFractions);
        return calculateTransformedPopulationFractionsFromBreakProportions(breakProportions);
    }

    static PopulationMixture.PopulationFractions calculatePopulationFractionsFromTransformedPopulationFractions(final List<Double> transformedPopulationFractions) {
        final List<Double> breakProportions = calculateBreakProportionsFromTransformedPopulationFractions(transformedPopulationFractions);
        final List<Double> populationFractions = calculatePopulationFractionsFromBreakProportions(breakProportions);
        return new PopulationMixture.PopulationFractions(populationFractions);
    }

    private static List<Double> calculatePopulationFractionsFromBreakProportions(final List<Double> breakProportions) {
        final int numPopulations = breakProportions.size() + 1;
        final List<Double> populationFractions = new ArrayList<>();
        double cumulativeSum = 0.;
        for (int populationIndex = 0; populationIndex < numPopulations - 1; populationIndex++) {
            final double populationFraction = (1. - cumulativeSum) * breakProportions.get(populationIndex);
            populationFractions.add(populationFraction);
            cumulativeSum += populationFraction;
        }
        populationFractions.add(1. - cumulativeSum);
        return new PopulationMixture.PopulationFractions(populationFractions);
    }

    private static List<Double> calculateBreakProportionsFromPopulationFractions(final PopulationMixture.PopulationFractions populationFractions) {
        final int numPopulations = populationFractions.size();
        final List<Double> breakProportions = new ArrayList<>();
        double cumulativeSum = 0.;
        for (int populationIndex = 0; populationIndex < numPopulations - 1; populationIndex++) {
            final double breakProportion = populationFractions.get(populationIndex) / (1. - cumulativeSum);
            breakProportions.add(breakProportion);
            cumulativeSum += populationFractions.get(populationIndex);
        }
        return breakProportions;
    }

    private static List<Double> calculateBreakProportionsFromTransformedPopulationFractions(final List<Double> transformedPopulationFractions) {
        final int numPopulations = transformedPopulationFractions.size() + 1;
        return IntStream.range(0, numPopulations - 1).boxed()
                .map(i -> new Sigmoid().value(transformedPopulationFractions.get(i) + Math.log(1. / (numPopulations - (i + 1)))))
                .collect(Collectors.toList());
    }

    private static List<Double> calculateTransformedPopulationFractionsFromBreakProportions(final List<Double> breakProportions) {
        final int numPopulations = breakProportions.size() + 1;
        return IntStream.range(0, numPopulations - 1).boxed()
                .map(i -> new Logit().value(breakProportions.get(i)) - Math.log(1. / (numPopulations - (i + 1))))
                .collect(Collectors.toList());
    }

    static double proposeTransformedPopulationFraction(final RandomGenerator rng, final Double currentTransformedPopulationFraction) {
        return rng.nextDouble() < 0.5
                ? currentTransformedPopulationFraction + new NormalDistribution(rng, 0., TumorHeterogeneitySamplers.PopulationMixtureSampler.transformedPopulationFractionProposalWidth).sample()
                : currentTransformedPopulationFraction + new NormalDistribution(rng, 0., 10 * TumorHeterogeneitySamplers.PopulationMixtureSampler.transformedPopulationFractionProposalWidth).sample();
    }

    private static double proposePloidy(final RandomGenerator rng,
                                    final double currentPloidy,
                                    final int maxTotalCopyNumber) {
        int numIterations = 0;
        final NormalDistribution normal = rng.nextDouble() < 0.5
                ? new NormalDistribution(rng, 0., TumorHeterogeneitySamplers.PopulationMixtureSampler.ploidyProposalWidth)
                : new NormalDistribution(rng, 0., 10 * TumorHeterogeneitySamplers.PopulationMixtureSampler.ploidyProposalWidth);
        while (numIterations < TumorHeterogeneitySamplers.PopulationMixtureSampler.MAX_NUM_PLOIDY_STEP_ITERATIONS) {
            final double proposedPloidy = currentPloidy + normal.sample();
            if (0 < proposedPloidy && proposedPloidy <= maxTotalCopyNumber) {
                return proposedPloidy;
            }
            numIterations++;
        }
        return currentPloidy;
    }
}

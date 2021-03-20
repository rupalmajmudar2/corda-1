package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.StateRef
import net.corda.core.internal.uncheckedCast
import net.corda.core.utilities.ProgressTracker

/**
 * Given a flow which uses reference states, the [WithReferencedStatesFlow] will execute the flow as a subFlow.
 * If the flow fails due to a [NotaryError.Conflict] for a reference state, then WithReferencedStatesFlow will be suspended until the
 * state refs for the reference states are consumed. In this case, a consumption means that:
 *
 * 1. the owner of the reference state has updated the state with a valid, notarised transaction
 * 2. the owner of the reference state has shared the update with the node attempting to run the flow which uses the
 *    reference state
 * 3. The node has successfully committed the transaction updating the reference state (and all the dependencies), and
 *    added the updated reference state to the vault.
 *
 * WARNING: Caution should be taken when using this flow as it facilitates automated re-running of flows which use
 * reference states. The flow using reference states should include checks to ensure that the reference data is
 * reasonable, especially if some economics transaction depends upon it.
 *
 * @param progressTracker a progress tracker instance.
 * @param flowLogicProducer a lambda which creates the [FlowLogic] instance using reference states. This will be executed at least once.
 * It is recommended a new [FlowLogic] instance be returned each time.
 */
class WithReferencedStatesFlow<T : Any> @JvmOverloads constructor(
        override val progressTracker: ProgressTracker = tracker(),
        private val flowLogicProducer: () -> FlowLogic<T>
) : FlowLogic<T>() {

    companion object {
        object ATTEMPT : ProgressTracker.Step("Attempting to run flow which uses reference states.")
        object RETRYING : ProgressTracker.Step("Reference states are out of date! Waiting for updated states...")
        object SUCCESS : ProgressTracker.Step("Flow ran successfully.")

        @JvmStatic
        fun tracker() = ProgressTracker(ATTEMPT, RETRYING, SUCCESS)
    }

    // This is not a sealed data class as that requires exposing Success and Conflict
    private interface FlowResult
    private data class Success<T : Any>(val value: T) : FlowResult
    private data class Conflict(val stateRefs: Set<StateRef>) : FlowResult

    /**
     * Process the flow result. We don't care about anything other than NotaryExceptions. If it is a
     * NotaryException but not a Conflict, then just rethrow. If it's a Conflict, then extract the reference
     * input state refs. Otherwise, if the flow completes successfully then return the result.
     */
    private fun processFlowResult(result: Any): FlowResult {
        return when (result) {
            is NotaryException -> {
                val error = result.error
                if (error is NotaryError.Conflict) {
                    val conflictingReferenceStateRefs = error.consumedStates.filter {
                        it.value.type == StateConsumptionDetails.ConsumedStateType.REFERENCE_INPUT_STATE
                    }.map { it.key }.toSet()
                    Conflict(conflictingReferenceStateRefs)
                } else {
                    throw result
                }
            }
            is FlowException -> throw result
            else -> Success(result)
        }
    }

    @Suspendable
    override fun call(): T {
        progressTracker.currentStep = ATTEMPT

        // Loop until the flow successfully completes. We need to
        // do this because there might be consecutive update races.
        while (true) {
            val flowLogic = flowLogicProducer()
            // Return a successful flow result or a FlowException.
            logger.info("Attempting to run the supplied flow ${flowLogic.javaClass.canonicalName}.")
            val result = try {
                subFlow(flowLogic)
            } catch (e: FlowException) {
                e
            }

            val processedResult = processFlowResult(result)

            // Return the flow result or wait for the StateRefs to be updated and try again.
            // 1. If a success, we can always cast the return type to T.
            // 2. If there is a conflict, then suspend this flow, only waking it up when the conflicting reference
            //    states have been updated.
            @Suppress("UNCHECKED_CAST")
            when (processedResult) {
                is Success<*> -> {
                    logger.info("Flow ${flowLogic.javaClass.canonicalName} completed successfully.")
                    progressTracker.currentStep = SUCCESS
                    return uncheckedCast(processedResult.value)
                }
                is Conflict -> {
                    val conflicts = processedResult.stateRefs
                    logger.info("Flow ${flowLogic.javaClass.name} failed due to reference state conflicts: $conflicts.")

                    // Only set currentStep to FAILURE once.
                    if (progressTracker.currentStep != RETRYING) {
                        progressTracker.currentStep = RETRYING
                    }

                    // Suspend this flow.
                    waitForStateConsumption(conflicts)
                    logger.info("All referenced states have been updated. Retrying flow...")
                }
            }
        }
    }
}

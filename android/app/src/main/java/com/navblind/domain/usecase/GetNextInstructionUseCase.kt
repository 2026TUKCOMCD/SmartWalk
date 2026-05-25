package com.navblind.domain.usecase

import com.navblind.domain.model.Instruction
import com.navblind.domain.model.Route
import javax.inject.Inject

class GetNextInstructionUseCase @Inject constructor() {
    operator fun invoke(route: Route, currentIndex: Int): Instruction? =
        route.instructions.getOrNull(currentIndex + 1)

    fun hasMore(route: Route, currentIndex: Int): Boolean =
        currentIndex + 1 < route.instructions.size

    fun isLastInstruction(route: Route, currentIndex: Int): Boolean =
        currentIndex >= route.instructions.size - 1
}

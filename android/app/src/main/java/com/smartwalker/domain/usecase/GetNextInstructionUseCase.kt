package com.smartwalker.domain.usecase

import com.smartwalker.domain.model.Instruction
import com.smartwalker.domain.model.Route
import javax.inject.Inject

class GetNextInstructionUseCase @Inject constructor() {
    operator fun invoke(route: Route, currentIndex: Int): Instruction? =
        route.instructions.getOrNull(currentIndex + 1)

    fun hasMore(route: Route, currentIndex: Int): Boolean =
        currentIndex + 1 < route.instructions.size

    fun isLastInstruction(route: Route, currentIndex: Int): Boolean =
        currentIndex >= route.instructions.size - 1
}

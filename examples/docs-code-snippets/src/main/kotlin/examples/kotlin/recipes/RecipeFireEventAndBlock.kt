package examples.kotlin.recipes

import com.ing.baker.recipe.kotlindsl.ExperimentalDsl
import com.ing.baker.recipe.kotlindsl.recipe

@ExperimentalDsl
object RecipeFireEventAndBlock {
    val recipe = recipe("example") {
        defaultFailureStrategy = fireEventAndBlock("MyEvent")
    }
}
package examples.java.recipes;

import com.ing.baker.recipe.javadsl.InteractionFailureStrategy;
import com.ing.baker.recipe.javadsl.Recipe;

import java.time.Duration;

public class RecipeRetryWithBackOfWithFireEventAndBlock {

    public final static Recipe recipe = new Recipe("example")
        .withDefaultFailureStrategy(
            new InteractionFailureStrategy.RetryWithIncrementalBackoffBuilder()
                .withInitialDelay(Duration.ofMillis(100))
                .withBackoffFactor(2.0)
                .withMaxTimeBetweenRetries(Duration.ofSeconds(100))
                .withDeadline(Duration.ofHours(24))
                .withFireEventAndBlock("RetriesExhausted")
                .build()
        );
}

package com.ing.baker.runtime.akka.actor.recipe_manager

import com.ing.baker.BakerRuntimeTestBase
import com.ing.baker.runtime.akka.actor.recipe_manager.RecipeManagerActor.RecipeAdded
import com.ing.baker.runtime.akka.actor.serialization.{AkkaSerializerProvider, SerializationSpec}
import com.ing.baker.runtime.serialization.Encryption
import org.scalatest.TryValues._
import com.ing.baker.runtime.akka.actor.serialization.SerializedDataProto._

class RecipeManagerActorProtoSpec extends BakerRuntimeTestBase {

  override def actorSystemName = "RecipeManagerProtoSpec"

  //This test was added to ensure backwards compatibility with V1 of Baker
  "The RecipeAdded message" should {
    "allow for an empty timestamp and default it to 0" in {
      implicit val serializersProvider: AkkaSerializerProvider = AkkaSerializerProvider(defaultActorSystem, Encryption.NoEncryption)
      val recipeAddedProto = RecipeManagerProto.recipeAddedProto

      val recipeAddedMessage = recipeAddedProto
        .toProto(RecipeAdded(SerializationSpec.IntermediateLanguage.recipeGen.sample.get, 99L))
        .copy(timeStamp = None)

      val result = recipeAddedProto.fromProto(recipeAddedMessage)
      result.get //This triggers the serialization logic and if it fails it throws a readable exception.
      result.success.value.timeStamp shouldBe 0L
    }
  }
}

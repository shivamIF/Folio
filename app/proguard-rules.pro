# kotlinx.serialization: keep generated serializers for the document model.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.folio.core.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.folio.core.model.**$$serializer { *; }

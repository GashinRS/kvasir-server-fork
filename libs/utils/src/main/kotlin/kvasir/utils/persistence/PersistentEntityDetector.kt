package kvasir.utils.persistence

import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.persistence.PersistentEntity
import org.jboss.jandex.AnnotationTarget
import org.jboss.jandex.DotName
import org.jboss.jandex.IndexView

@ApplicationScoped
class PersistentEntityDetector(
    private val jandexIndex: IndexView
) {

    lateinit var detectedEntityClasses: Set<Class<out PersistentEntity>>

    @PostConstruct
    fun init() {
        // 1. Define the Annotation name
        // Replace with your actual annotation class name
        val annotationName = DotName.createSimple(Persistent::class.qualifiedName)

        // 2. Get all instances of this annotation found in the build
        detectedEntityClasses = jandexIndex.getAnnotations(annotationName).mapNotNull { instance ->
            // 3. Jandex finds the annotation usage anywhere (methods, fields, classes).
            // We must check if the target is actually a CLASS.
            if (instance.target().kind() == AnnotationTarget.Kind.CLASS) {
                val classInfo = instance.target().asClass()
                Class.forName(
                    classInfo.name().toString(),
                    false,
                    Thread.currentThread().contextClassLoader
                ) as Class<out PersistentEntity>
            } else {
                null
            }
        }.toSet()
    }

    fun getDetectedEntityClasses(storageLevel: StorageLevel): Set<Class<out PersistentEntity>> {
        return detectedEntityClasses.filter { entityClass ->
            val persistentAnnotation = entityClass.getAnnotation(Persistent::class.java)
            persistentAnnotation.storageLevel == storageLevel
        }.toSet()
    }

}
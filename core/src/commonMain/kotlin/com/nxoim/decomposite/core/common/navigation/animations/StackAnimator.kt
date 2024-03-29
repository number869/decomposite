package com.nxoim.decomposite.core.common.navigation.animations

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.zIndex
import com.arkivanov.decompose.Child
import com.arkivanov.decompose.InternalDecomposeApi
import com.arkivanov.decompose.hashString
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.items
import com.arkivanov.decompose.value.Value
import com.nxoim.decomposite.core.common.navigation.DecomposeChildInstance
import com.nxoim.decomposite.core.common.ultils.ImmutableThingHolder
import com.nxoim.decomposite.core.common.ultils.OnDestinationDisposeEffect
import kotlinx.coroutines.launch

@OptIn(InternalDecomposeApi::class)
@Composable
fun <C : Any, T : DecomposeChildInstance> StackAnimator(
    stackValue: ImmutableThingHolder<Value<ChildStack<C, T>>>,
    stackAnimatorScope: StackAnimatorScope<C>,
    modifier: Modifier = Modifier,
    onBackstackChange: (stackEmpty: Boolean) -> Unit,
    excludeStartingDestination: Boolean = false,
    allowBatchRemoval: Boolean = true,
    animations: AnimatorChildrenConfigurations<C>.() -> ContentAnimations,
    content: @Composable (child: Child.Created<C, T>) -> Unit,
) = with(stackAnimatorScope) {
    key(stackAnimatorScope.key) {
        val holder = rememberSaveableStateHolder()
        var sourceStack by remember { mutableStateOf(stackValue.thing.value) }
        val removingChildren = remember { mutableStateListOf<C>() }
        val cachedChildrenInstances = remember {
            mutableStateMapOf<C, Child.Created<C, T>>().apply {
                putAll(
                    stackValue.thing.items.subList(
                        if (excludeStartingDestination) 1 else 0,
                        stackValue.thing.items.size
                    ).associateBy { it.configuration }
                )
            }
        }

        LaunchedEffect(Unit) {
            // check on startup if there's animation data left for nonexistent children, which
            // can happen during a configuration change
            launch {
                removeStaleAnimationDataCache(nonStale = sourceStack.items.fastMap { it.configuration } )
            }

            stackValue.thing.subscribe { newStackRaw ->
                onBackstackChange(newStackRaw.items.size <= 1)
                val oldStack = sourceStack.items
                val newStack = newStackRaw.items.subList(
                    if (excludeStartingDestination) 1 else 0,
                    stackValue.thing.items.size
                )

                val childrenToRemove = oldStack.filter { it !in newStack && it.configuration !in removingChildren }
                val batchRemoval = childrenToRemove.size > 1 && allowBatchRemoval

                // cancel removal of items that appeared again in the stack
                removingChildren.removeAll(newStackRaw.items.map { it.configuration })

                if (batchRemoval) {
                    // remove from cache and everything all children, except the last one,
                    // which will be animated
                    val itemsToRemoveImmediately = childrenToRemove.subList(0, childrenToRemove.size - 1)
                    itemsToRemoveImmediately.forEach { (configuration, _) ->
                        cachedChildrenInstances.remove(configuration)
                    }
                    removingChildren.add(childrenToRemove.last().configuration)
                } else {
                    childrenToRemove.forEach {
                        removingChildren.add(it.configuration)
                    }
                }

                sourceStack = newStackRaw

                cachedChildrenInstances.putAll(newStack.associateBy { it.configuration })
            }
        }

        Box(modifier) {
            cachedChildrenInstances.forEach { (child, cachedInstance) ->
                key(child) {
                    val inStack = !removingChildren.contains(child)
                    val instance by remember {
                        derivedStateOf {
                            sourceStack.items.find { it.configuration == child } ?: cachedInstance
                        }
                    }

                    val index = if (inStack)
                        sourceStack.items.indexOf(instance)
                    else
                        -(removingChildren.indexOf(child) + 1)

                    val indexFromTop = if (inStack)
                        sourceStack.items.size - index - 1
                    else
                        -(removingChildren.indexOf(child) + 1)

                    val allAnimations = animations(
                        AnimatorChildrenConfigurations(
                            sourceStack.items.elementAt(index - 1).configuration,
                            child,
                            sourceStack.items.elementAt(index + 1).configuration
                        )
                    )

                    val animData = remember(allAnimations) {
                        getOrCreateAnimationData(
                            key = child,
                            source = allAnimations,
                            initialIndex = index,
                            initialIndexFromTop = indexFromTop
                        )
                    }

                    val allowingAnimation = indexFromTop <= (animData.renderUntils.min())

                    val animating by remember {
                        derivedStateOf {
                            animData.scopes.any { it.value.animationStatus.animating }
                        }
                    }

                    val displaying = remember(animating, allowingAnimation) {
                        val requireVisibilityInBack = animData.requireVisibilityInBackstacks.fastAny { it }
                        val renderingBack = allowingAnimation && animating
                        val renderTopAndAnimatedBack = indexFromTop < 1 || renderingBack
                        if (requireVisibilityInBack) allowingAnimation else renderTopAndAnimatedBack
                    }

                    LaunchedEffect(allowingAnimation, inStack) {
                        stackAnimatorScope.updateChildAnimPrerequisites(
                            child,
                            allowingAnimation,
                            inStack
                        )
                    }

                    // launch animations if there's changes
                    LaunchedEffect(indexFromTop, index) {
                        animData.scopes.forEach { (_, scope) ->
                            launch {
                                scope.update(
                                    index,
                                    indexFromTop,
                                    animate = scope.indexFromTop != indexFromTop || indexFromTop < 1
                                )

                                // after animating, if is not in stack
                                if (!inStack) cachedChildrenInstances.remove(child)
                            }
                        }
                    }

                    // will get triggered upon removal
                    OnDestinationDisposeEffect(
                        instance.configuration.hashString() + stackAnimatorScope.key + "OnDestinationDisposeEffect",
                        waitForCompositionRemoval = true,
                        componentContext = instance.instance.componentContext
                    ) {
                        removingChildren.remove(child)
                        removeAnimationDataFromCache(child)
                        holder.removeState(childHolderKey(child))
                    }

                    if (displaying) holder.SaveableStateProvider(childHolderKey(child)) {
                        Box(
                            Modifier.zIndex((-indexFromTop).toFloat()).accumulate(animData.modifiers),
                            content = { content(instance) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(InternalDecomposeApi::class)
private fun <C : Any> childHolderKey(child: C) =
    child.hashString() + " StackAnimator SaveableStateHolder"

data class AnimatorChildrenConfigurations<C : Any>(
    val previousChild: C?,
    val currentChild: C,
    val nextChild: C?
)
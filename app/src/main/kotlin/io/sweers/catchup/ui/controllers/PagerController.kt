/*
 * Copyright (c) 2017 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.catchup.ui.controllers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Parcelable
import android.support.annotation.ColorInt
import android.support.annotation.ColorRes
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.design.widget.AppBarLayout
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.TabLayout
import android.support.graphics.drawable.VectorDrawableCompat
import android.support.v4.content.ContextCompat
import android.support.v4.view.ViewPager
import android.support.v4.view.animation.FastOutSlowInInterpolator
import android.support.v4.view.animation.LinearOutSlowInInterpolator
import android.support.v7.widget.Toolbar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import butterknife.BindView
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.support.RouterPagerAdapter
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.jakewharton.rxbinding2.support.design.widget.RxAppBarLayout
import com.uber.autodispose.kotlin.autoDisposeWith
import dagger.Subcomponent
import dagger.android.AndroidInjector
import io.sweers.catchup.P
import io.sweers.catchup.R
import io.sweers.catchup.data.RemoteConfigKeys
import io.sweers.catchup.injection.ConductorInjection
import io.sweers.catchup.injection.scopes.PerController
import io.sweers.catchup.rx.PredicateConsumer
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.ui.Scrollable
import io.sweers.catchup.ui.activity.SettingsActivity
import io.sweers.catchup.ui.base.ButterKnifeController
import io.sweers.catchup.ui.base2.NewServiceController
import io.sweers.catchup.util.clearLightStatusBar
import io.sweers.catchup.util.isInNightMode
import io.sweers.catchup.util.resolveAttribute
import io.sweers.catchup.util.setLightStatusBar
import java.util.Arrays
import javax.inject.Inject

// TODO Temporary till static services are removed
fun ServiceMeta.toStaticService() = StaticService(
    name,
    icon,
    themeColor,
    { NewServiceController.newInstance(id) }
)

data class StaticService(@StringRes val name: Int,
    @DrawableRes val icon: Int,
    @ColorRes val accent: Int,
    val instantiator: () -> Controller)

class PagerController : ButterKnifeController {

  companion object {

    private const val SETTINGS_ACTIVITY_REQUEST = 100
    private const val PAGE_TAG = "PagerController.pageTag"
    private lateinit var services: Array<StaticService>
    private val staticServices = listOf(
        StaticService(R.string.dribbble,
            R.drawable.logo_dribbble,
            R.color.dribbbleAccent,
            { DribbbleController() }))
  }

  private lateinit var resolvedColorCache: IntArray
  private val argbEvaluator = ArgbEvaluator()

  @Inject lateinit var serviceMetas: Map<String, @JvmSuppressWildcards ServiceMeta>
  @Inject lateinit var remoteConfig: FirebaseRemoteConfig
  @BindView(R.id.pager_controller_root) lateinit var rootLayout: CoordinatorLayout
  @BindView(R.id.tab_layout) lateinit var tabLayout: TabLayout
  @BindView(R.id.view_pager) lateinit var viewPager: ViewPager
  @BindView(R.id.toolbar) lateinit var toolbar: Toolbar
  @BindView(R.id.appbarlayout) lateinit var appBarLayout: AppBarLayout
  private var statusBarColorAnimator: ValueAnimator? = null
  private var tabLayoutColorAnimator: Animator? = null
  private var colorNavBar: Boolean
    set(value) {
      P.ThemeNavigationBar.put(value).commit()
    }
    get() = P.ThemeNavigationBar.get()
  private var tabLayoutIsPinned = false
  private var canAnimateColor = true
  private var lastPosition = 0
  private var pagerAdapter: RouterPagerAdapter

  constructor() : super()

  @Suppress("unused")
  constructor(args: Bundle) : super(args)

  init {
    pagerAdapter = object : RouterPagerAdapter(this) {
      override fun configureRouter(router: Router, position: Int) {
        if (!router.hasRootController()) {
          router.setRoot(RouterTransaction.with(services[position].instantiator())
              .tag(PAGE_TAG))
        }
      }

      override fun getCount() = services.size

      override fun getPageTitle(position: Int) = ""
    }
  }

  override fun onContextAvailable(context: Context) {
    ConductorInjection.inject(this)
    // TODO Temporary till services are all migrated
    services = (serviceMetas.values.map { it.toStaticService() } + staticServices).toTypedArray()

    resolvedColorCache = IntArray(services.size)
    // Invalidate the color cache up front
    Arrays.fill(resolvedColorCache, R.color.no_color)
    super.onContextAvailable(context)
  }

  override fun onSaveViewState(view: View, outState: Bundle) {
    // Save the appbarlayout state to restore it on the other side
    (appBarLayout.layoutParams as CoordinatorLayout.LayoutParams).behavior?.let { behavior ->
      outState.run {
        putParcelable("collapsingToolbarState",
            behavior.onSaveInstanceState(rootLayout, appBarLayout))
      }
    }
    super.onSaveViewState(view, outState)
  }

  override fun onRestoreViewState(view: View, savedViewState: Bundle) {
    super.onRestoreViewState(view, savedViewState)
    with(savedViewState) {
      getParcelable<Parcelable>("collapsingToolbarState")?.let {
        (appBarLayout.layoutParams as CoordinatorLayout.LayoutParams).behavior
            ?.onRestoreInstanceState(rootLayout, appBarLayout, it)
      }
    }
  }

  override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View =
      inflater.inflate(R.layout.controller_pager, container, false)

  override fun bind(view: View) = PagerController_ViewBinding(this, view)

  override fun onAttach(view: View) {
    ConductorInjection.inject(this)
    super.onAttach(view)
  }

  override fun onViewBound(view: View) {
    super.onViewBound(view)

    @ColorInt val colorPrimaryDark = view.context.resolveAttribute(R.attr.colorPrimaryDark)
    val isInNightMode = view.context.isInNightMode()
    if (!isInNightMode) {
      // Start with a light status bar in normal mode
      appBarLayout.setLightStatusBar()
    }
    RxAppBarLayout.offsetChanges(appBarLayout)
        .distinctUntilChanged()
        .doOnNext(object : PredicateConsumer<Int>() {
          @Throws(Exception::class)
          override fun test(verticalOffset: Int) = verticalOffset == -toolbar.height

          @Throws(Exception::class)
          override fun acceptActual(value: Int) {
            statusBarColorAnimator?.cancel()
            tabLayoutIsPinned = true
            val newStatusColor = this@PagerController.getAndSaveColor(
                tabLayout.selectedTabPosition)
            statusBarColorAnimator = ValueAnimator.ofArgb(colorPrimaryDark, newStatusColor)
                .apply {
                  addUpdateListener { animation ->
                    this@PagerController.activity!!
                        .window.statusBarColor = animation.animatedValue as Int
                  }
                  duration = 200
                  interpolator = LinearOutSlowInInterpolator()
                  start()
                }
            appBarLayout.clearLightStatusBar()
          }
        })
        .doOnNext(object : PredicateConsumer<Int>() {
          @Throws(Exception::class)
          override fun acceptActual(value: Int) {
            val wasPinned = tabLayoutIsPinned
            tabLayoutIsPinned = false
            if (wasPinned) {
              statusBarColorAnimator?.cancel()
              statusBarColorAnimator = ValueAnimator.ofArgb(this@PagerController.activity!!
                  .window
                  .statusBarColor, colorPrimaryDark).apply {
                addUpdateListener { animation ->
                  this@PagerController.activity!!
                      .window.statusBarColor = animation.animatedValue as Int
                }
                duration = 200
                interpolator = DecelerateInterpolator()
                if (!isInNightMode) {
                  appBarLayout.setLightStatusBar()
                }
                start()
              }
            }
          }

          @Throws(Exception::class)
          override fun test(verticalOffset: Int) = verticalOffset != -toolbar.height
        })
        .autoDisposeWith(this)
        .subscribe()
    toolbar.inflateMenu(R.menu.main)
    toolbar.setOnMenuItemClickListener { item ->
      when (item.itemId) {
        R.id.settings -> {
          startActivityForResult(
              Intent(activity, SettingsActivity::class.java), SETTINGS_ACTIVITY_REQUEST)
          return@setOnMenuItemClickListener true
        }
      }
      false
    }

    // Initial title
    toolbar.title = resources!!.getString(services[0].name)

    // Set the initial color
    @ColorInt val initialColor = getAndSaveColor(0)
    tabLayout.setBackgroundColor(initialColor)
    viewPager.adapter = pagerAdapter
    tabLayout.setupWithViewPager(viewPager, false)

    // Set icons
    for (i in services.indices) {
      val service = services[i]
      val d = VectorDrawableCompat.create(resources!!, service.icon, null)
      tabLayout.getTabAt(i)!!.icon = d
    }

    // Animate color changes
    // adapted from http://kubaspatny.github.io/2014/09/18/viewpager-background-transition/
    viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
      override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
        if (canAnimateColor) {
          val color: Int = if (position < pagerAdapter.count - 1 && position < services.size - 1) {
            argbEvaluator.evaluate(positionOffset,
                getAndSaveColor(position),
                getAndSaveColor(position + 1)) as Int
          } else {
            getAndSaveColor(services.size - 1)
          }
          tabLayout.setBackgroundColor(color)
          if (tabLayoutIsPinned) {
            activity?.window?.statusBarColor = color
          }
          if (colorNavBar) {
            activity?.window?.navigationBarColor = color
          }
        }
      }

      override fun onPageSelected(position: Int) {}

      override fun onPageScrollStateChanged(state: Int) {
        if (state == ViewPager.SCROLL_STATE_IDLE) {
          canAnimateColor = true
        }
      }
    })

    tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
      override fun onTabSelected(tab: TabLayout.Tab) {
        val position = tab.position
        toolbar.setTitle(services[position].name)

        // If we're switching between more than one page, we just want to manually set the color
        // once rather than let the usual page scroll logic cycle through all the colors in a weird
        // flashy way.
        if (Math.abs(lastPosition - position) > 1) {
          canAnimateColor = false
          // Start with the current tablayout color to feel more natural if we're in between
          @ColorInt val startColor = (tabLayout.background as ColorDrawable).color
          @ColorInt val endColor = getAndSaveColor(position)
          tabLayoutColorAnimator?.cancel()
          ValueAnimator.ofFloat(0f, 1f)
              .run {
                interpolator = FastOutSlowInInterpolator()  // TODO Use singleton
                duration = 400
                addListener(object : AnimatorListenerAdapter() {
                  override fun onAnimationStart(animator: Animator, isReverse: Boolean) {
                    tabLayoutColorAnimator = animator
                  }

                  override fun onAnimationEnd(animator: Animator) {
                    removeAllUpdateListeners()
                    removeListener(this)
                    tabLayoutColorAnimator = null
                  }
                })
                addUpdateListener { animator ->
                  @ColorInt val color = argbEvaluator.evaluate(animator.animatedValue as Float,
                      startColor,
                      endColor) as Int
                  tabLayout.setBackgroundColor(color)
                  if (tabLayoutIsPinned) {
                    activity?.window?.statusBarColor = color
                  }
                  if (colorNavBar) {
                    activity?.window?.navigationBarColor = color
                  }
                }
                start()
              }
        }
        lastPosition = position
      }

      override fun onTabUnselected(tab: TabLayout.Tab) {}

      override fun onTabReselected(tab: TabLayout.Tab) {
        pagerAdapter.getRouter(tab.position)?.getControllerWithTag(PAGE_TAG)?.let {
          if (it is Scrollable) {
            it.onRequestScrollToTop()
            appBarLayout.setExpanded(true, true)
          }
        }
      }
    })
  }

  @ColorInt private fun getAndSaveColor(position: Int): Int {
    if (resolvedColorCache[position] == R.color.no_color) {
      resolvedColorCache[position] = ContextCompat.getColor(activity!!, services[position].accent)
    }
    return resolvedColorCache[position]
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == SETTINGS_ACTIVITY_REQUEST) {
      if (resultCode == SettingsActivity.NIGHT_MODE_UPDATED) {
        activity?.recreate()
      } else {
        if (remoteConfig.getBoolean(RemoteConfigKeys.THEME_NAV_BAR_ENABLED)) {
          // Update the nav bar with whatever prefs we had
          val navBarColor = if (P.ThemeNavigationBar.get()) {
            (tabLayout.background as ColorDrawable).color
          } else {
            Color.BLACK // TODO is this actually a safe default?
          }
          activity?.window?.navigationBarColor = navBarColor
        }
      }
    }
  }

  @PerController
  @Subcomponent
  interface Component : AndroidInjector<PagerController> {

    @Subcomponent.Builder
    abstract class Builder : AndroidInjector.Builder<PagerController>()
  }
}

/**
 * SONIX Adaptive Performance Engine - UI Controller
 * Synchronizes hardware tier classifications with WebView CSS rendering and DOM optimizations.
 */
(function() {
  'use strict';

  var currentMode = 'smart';
  var currentTier = 'balanced';
  var isDynamicallyThrottled = false;
  var throttleReason = '';
  var isNativeConnected = false;
  var isUpdatingFromNative = false;
  var imageObserver = null;

  var SonixPerformanceUI = {
    init: function() {
      this.checkNativeBridge();
      this.fetchInitialState();
      this.bindSettingsEvents();
      this.setupGlobalCallback();
      this.setupLazyLoading();
      this.measureStartup();
    },

    checkNativeBridge: function() {
      isNativeConnected = !!(window.SonixPerformance &&
        (typeof window.SonixPerformance.isConnected === 'function' ? window.SonixPerformance.isConnected() : typeof window.SonixPerformance.getPerformanceMode === 'function'));
    },

    fetchInitialState: function() {
      this.checkNativeBridge();
      try {
        if (isNativeConnected) {
          currentMode = window.SonixPerformance.getPerformanceMode() || 'smart';
          currentTier = window.SonixPerformance.getPerformanceTier() || 'balanced';
          try {
            var specsJson = window.SonixPerformance.getDeviceSpecsJson();
            if (specsJson) {
              var specs = JSON.parse(specsJson);
              isDynamicallyThrottled = !!specs.is_dynamically_throttled;
              throttleReason = specs.throttle_reason || '';
            }
          } catch (_) {}
        } else {
          currentMode = localStorage.getItem('sonix_perf_mode') || 'smart';
          currentTier = localStorage.getItem('sonix_perf_tier') || 'balanced';
        }
      } catch (e) {
        currentMode = 'smart';
        currentTier = 'balanced';
      }

      this.applyTier(currentTier);
      this.updateSettingsUi(currentMode, currentTier);
    },

    applyTier: function(tierName) {
      if (!tierName) tierName = 'balanced';
      currentTier = tierName;

      var docEl = document.documentElement;
      docEl.classList.remove('perf-tier-performance', 'perf-tier-balanced', 'perf-tier-premium');
      docEl.classList.add('perf-tier-' + tierName);

      try {
        localStorage.setItem('sonix_perf_tier', tierName);
      } catch (_) {}

      this.updateBadges(tierName);
    },

    updateBadges: function(tierName) {
      var badge = document.getElementById('perf-active-tier-label');
      var desc = document.getElementById('settings-perf-status-desc');
      if (!badge) return;

      var tierLabels = {
        performance: '🚀 أداء عالي (مخفف للسرعة)',
        balanced: '⚖️ متوازن (سلس ومستقر)',
        premium: '🎬 سينمائي كامل (مؤثرات فائقة)'
      };

      var baseLabel = tierLabels[tierName] || tierName;
      if (isDynamicallyThrottled && throttleReason) {
        baseLabel += ' [مخفض مؤقتاً: ' + throttleReason + ']';
      }
      if (!isNativeConnected) {
        baseLabel += ' (وضع محلي احتياطي)';
      }

      badge.textContent = baseLabel;

      if (desc) {
        if (!isNativeConnected) {
          desc.textContent = 'الوضع المحلي (الجسر غير متاح) - المستوى: ' + (tierLabels[tierName] || tierName);
        } else if (currentMode === 'smart') {
          desc.textContent = isDynamicallyThrottled
            ? 'تلقائي ذكي (Smart) - تم تخفيض الأداء مؤقتاً بسبب ضغط الذاكرة'
            : 'تلقائي ذكي (Smart) - المستوى النشط: ' + (tierLabels[tierName] || tierName);
        } else {
          desc.textContent = 'اختيار يدوي محدد: ' + (tierLabels[tierName] || tierName);
        }
      }
    },

    updateSettingsUi: function(mode, tier) {
      var select = document.getElementById('settings-performance-mode');
      if (select && select.value !== mode) {
        isUpdatingFromNative = true;
        select.value = mode;
        isUpdatingFromNative = false;
      }
      this.updateBadges(tier);
    },

    bindSettingsEvents: function() {
      var self = this;
      var select = document.getElementById('settings-performance-mode');
      if (!select) return;

      select.addEventListener('change', function(e) {
        if (isUpdatingFromNative) return;
        var newMode = e.target.value;
        currentMode = newMode;
        isDynamicallyThrottled = false;
        throttleReason = '';

        try {
          localStorage.setItem('sonix_perf_mode', newMode);
        } catch (_) {}

        var newTier = 'balanced';
        self.checkNativeBridge();
        if (isNativeConnected && typeof window.SonixPerformance.setPerformanceMode === 'function') {
          newTier = window.SonixPerformance.setPerformanceMode(newMode);
        } else {
          if (newMode === 'performance') newTier = 'performance';
          else if (newMode === 'premium') newTier = 'premium';
          else newTier = 'balanced';
        }

        self.applyTier(newTier);
        self.updateBadges(newTier);

        if (typeof showToast === 'function') {
          showToast('تم حفظ وضع الأداء: ' + select.options[select.selectedIndex].text);
        }
      });
    },

    setupGlobalCallback: function() {
      var self = this;
      window.onSonixTierChanged = function(tierName, modeName, isThrottled, reason) {
        isNativeConnected = true;
        currentMode = modeName || currentMode;
        isDynamicallyThrottled = !!isThrottled;
        throttleReason = reason || '';
        self.applyTier(tierName);
        self.updateSettingsUi(currentMode, tierName);
      };
    },

    setupLazyLoading: function() {
      if ('IntersectionObserver' in window) {
        imageObserver = new IntersectionObserver(function(entries, observer) {
          entries.forEach(function(entry) {
            if (entry.isIntersecting) {
              var img = entry.target;
              var src = img.getAttribute('data-lazy-src');
              if (src) {
                img.src = src;
                img.removeAttribute('data-lazy-src');
                img.classList.add('loaded');
              }
              observer.unobserve(img);
            }
          });
        }, {
          rootMargin: '250px 0px',
          threshold: 0.01
        });
      }

      this.scanLazyImages();
    },

    scanLazyImages: function(container) {
      var root = container || document;
      var lazyImages = root.querySelectorAll('img[data-lazy-src]');
      if (!lazyImages || lazyImages.length === 0) return;

      if (imageObserver) {
        for (var i = 0; i < lazyImages.length; i++) {
          imageObserver.observe(lazyImages[i]);
        }
      } else {
        // Fallback for older WebViews
        for (var j = 0; j < lazyImages.length; j++) {
          var img = lazyImages[j];
          img.src = img.getAttribute('data-lazy-src');
          img.removeAttribute('data-lazy-src');
          img.classList.add('loaded');
        }
      }
    },

    measureStartup: function() {
      if (window.performance && window.performance.now) {
        var startupMs = Math.round(window.performance.now());
        if (window.SonixPerformance && typeof window.SonixPerformance.recordUiStartup === 'function') {
          window.SonixPerformance.recordUiStartup(startupMs);
        }
      }
    },

    measureNavigation: function(sectionName, fn) {
      var t0 = window.performance ? window.performance.now() : Date.now();
      try {
        if (typeof fn === 'function') fn();
      } finally {
        var t1 = window.performance ? window.performance.now() : Date.now();
        var duration = Math.round(t1 - t0);
        if (window.SonixPerformance && typeof window.SonixPerformance.recordNavigationLatency === 'function') {
          window.SonixPerformance.recordNavigationLatency(sectionName || 'unknown', duration);
        }
      }
    }
  };

  window.SonixPerformanceUI = SonixPerformanceUI;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      SonixPerformanceUI.init();
    });
  } else {
    SonixPerformanceUI.init();
  }
})();

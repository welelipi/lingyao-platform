/* ============================================
   凌瑶智数 LingYao Digital · 交互脚本
   ============================================ */

(function () {
  'use strict';

  // ==========================================
  // 1. 滚动进度条
  // ==========================================
  const scrollProgress = document.getElementById('scrollProgress');
  function updateScrollProgress() {
    const scrollTop = window.pageYOffset || document.documentElement.scrollTop;
    const scrollHeight = document.documentElement.scrollHeight - window.innerHeight;
    const progress = (scrollTop / scrollHeight) * 100;
    if (scrollProgress) scrollProgress.style.width = progress + '%';
  }
  window.addEventListener('scroll', updateScrollProgress, { passive: true });

  // ==========================================
  // 2. 导航栏滚动效果
  // ==========================================
  const navbar = document.getElementById('navbar');
  function updateNavbar() {
    if (!navbar) return;
    if (window.pageYOffset > 30) {
      navbar.classList.add('scrolled');
    } else {
      navbar.classList.remove('scrolled');
    }
  }
  window.addEventListener('scroll', updateNavbar, { passive: true });

  // ==========================================
  // 3. 移动端菜单切换
  // ==========================================
  const navToggle = document.getElementById('navToggle');
  const navMenu = document.getElementById('navMenu');
  if (navToggle && navMenu) {
    navToggle.addEventListener('click', () => {
      navMenu.classList.toggle('open');
    });

    // 点击菜单项关闭
    document.querySelectorAll('.nav-link').forEach(link => {
      link.addEventListener('click', () => navMenu.classList.remove('open'));
    });
  }

  // ==========================================
  // 4. 粒子背景动画
  // ==========================================
  const canvas = document.getElementById('particleCanvas');
  if (canvas) {
    const ctx = canvas.getContext('2d');
    let particles = [];
    let w, h;

    function resize() {
      w = canvas.width = canvas.offsetWidth;
      h = canvas.height = canvas.offsetHeight;
    }

    class Particle {
      constructor() {
        this.x = Math.random() * w;
        this.y = Math.random() * h;
        this.size = Math.random() * 2 + 0.5;
        this.speedX = (Math.random() - 0.5) * 0.4;
        this.speedY = (Math.random() - 0.5) * 0.4;
        this.opacity = Math.random() * 0.5 + 0.2;
      }

      update() {
        this.x += this.speedX;
        this.y += this.speedY;

        if (this.x < 0 || this.x > w) this.speedX *= -1;
        if (this.y < 0 || this.y > h) this.speedY *= -1;
      }

      draw() {
        ctx.beginPath();
        ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(0, 212, 255, ${this.opacity})`;
        ctx.fill();
      }
    }

    function connectParticles() {
      for (let i = 0; i < particles.length; i++) {
        for (let j = i + 1; j < particles.length; j++) {
          const dx = particles[i].x - particles[j].x;
          const dy = particles[i].y - particles[j].y;
          const dist = Math.sqrt(dx * dx + dy * dy);

          if (dist < 120) {
            ctx.beginPath();
            ctx.strokeStyle = `rgba(0, 212, 255, ${0.15 * (1 - dist / 120)})`;
            ctx.lineWidth = 0.5;
            ctx.moveTo(particles[i].x, particles[i].y);
            ctx.lineTo(particles[j].x, particles[j].y);
            ctx.stroke();
          }
        }
      }
    }

    function initParticles() {
      particles = [];
      const count = Math.min(80, Math.floor((w * h) / 18000));
      for (let i = 0; i < count; i++) {
        particles.push(new Particle());
      }
    }

    function animate() {
      ctx.clearRect(0, 0, w, h);
      particles.forEach(p => {
        p.update();
        p.draw();
      });
      connectParticles();
      requestAnimationFrame(animate);
    }

    resize();
    initParticles();
    animate();

    window.addEventListener('resize', () => {
      resize();
      initParticles();
    });
  }

  // ==========================================
  // 5. 数字滚动动画
  // ==========================================
  function animateNumber(el) {
    const target = parseFloat(el.getAttribute('data-target'));
    const isDecimal = el.getAttribute('data-decimal') === 'true';
    const span = el.querySelector('span:first-child');
    const duration = 2000;
    const start = performance.now();

    function step(now) {
      const elapsed = now - start;
      const t = Math.min(elapsed / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      const current = target * eased;
      span.textContent = isDecimal ? current.toFixed(1) : Math.floor(current).toLocaleString();

      if (t < 1) {
        requestAnimationFrame(step);
      } else {
        span.textContent = isDecimal ? target.toFixed(1) : target.toLocaleString();
      }
    }
    requestAnimationFrame(step);
  }

  // ==========================================
  // 6. Reveal 滚动动画
  // ==========================================
  const revealEls = document.querySelectorAll('.reveal');
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry, i) => {
      if (entry.isIntersecting) {
        setTimeout(() => {
          entry.target.classList.add('in-view');
        }, i * 80);
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.1, rootMargin: '0px 0px -80px 0px' });

  revealEls.forEach(el => observer.observe(el));

  // Hero 数据动画
  const heroStats = document.querySelectorAll('.hero-stats .stat-num');
  const heroObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        animateNumber(entry.target);
        heroObserver.unobserve(entry.target);
      }
    });
  }, { threshold: 0.5 });
  heroStats.forEach(s => heroObserver.observe(s));

  // ==========================================
  // 7. 产品选项卡切换
  // ==========================================
  const tabBtns = document.querySelectorAll('.tab-btn');
  const productDetails = document.querySelectorAll('.product-detail');

  tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const tabId = btn.getAttribute('data-tab');

      tabBtns.forEach(b => b.classList.remove('active'));
      productDetails.forEach(p => p.classList.remove('active'));

      btn.classList.add('active');
      const target = document.getElementById('product-' + tabId);
      if (target) {
        target.classList.add('active');
        // 重置并初始化图表
        setTimeout(() => initProductChart(tabId), 100);
      }
    });
  });

  // ==========================================
  // 8. Chart.js 产品图表
  // ==========================================
  let chartsInitialized = {};

  function getGradient(ctx, color1, color2) {
    const gradient = ctx.createLinearGradient(0, 0, 0, 140);
    gradient.addColorStop(0, color1);
    gradient.addColorStop(1, color2);
    return gradient;
  }

  function initProductChart(type) {
    const charts = {
      geo: 'geoChart',
      cacs: 'cacsChart',
      aidd: 'aiddChart',
      por: 'porChart'
    };

    const canvasId = charts[type];
    if (!canvasId) return;
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;

    if (chartsInitialized[canvasId]) {
      chartsInitialized[canvasId].destroy();
    }

    const ctx = canvas.getContext('2d');
    const labels = ['1月', '2月', '3月', '4月', '5月', '6月', '7月'];

    const config = {
      geo: {
        labels: labels,
        datasets: [{
          label: '品牌健康度',
          data: [62, 65, 68, 71, 74, 78, 82.5],
          borderColor: '#00D4FF',
          backgroundColor: getGradient(ctx, 'rgba(0, 212, 255, 0.4)', 'rgba(0, 212, 255, 0)'),
          fill: true,
          tension: 0.4,
          pointBackgroundColor: '#00D4FF',
          pointBorderColor: '#0A1F3D',
          pointBorderWidth: 2,
          pointRadius: 4
        }]
      },
      cacs: {
        labels: labels,
        datasets: [
          {
            label: '实际销量',
            data: [120, 132, 145, 158, 172, 185, 198],
            borderColor: '#00D4FF',
            backgroundColor: 'transparent',
            tension: 0.3,
            pointRadius: 3
          },
          {
            label: '预测销量',
            data: [118, 134, 144, 161, 170, 188, 195],
            borderColor: '#FF8C42',
            backgroundColor: 'transparent',
            borderDash: [5, 5],
            tension: 0.3,
            pointRadius: 3
          }
        ]
      },
      aidd: {
        labels: labels,
        datasets: [{
          label: '研发管线数',
          data: [82, 89, 95, 102, 110, 118, 128],
          borderColor: '#4ADE80',
          backgroundColor: getGradient(ctx, 'rgba(74, 222, 128, 0.4)', 'rgba(74, 222, 128, 0)'),
          fill: true,
          tension: 0.4,
          pointRadius: 3
        }]
      },
      por: {
        labels: labels,
        datasets: [
          {
            label: '在办项目',
            data: [45, 52, 58, 65, 72, 80, 87],
            backgroundColor: 'rgba(255, 140, 66, 0.7)',
            borderColor: '#FF8C42',
            borderWidth: 1
          },
          {
            label: '已闭环',
            data: [38, 46, 51, 58, 65, 72, 80],
            backgroundColor: 'rgba(74, 222, 128, 0.7)',
            borderColor: '#4ADE80',
            borderWidth: 1
          }
        ]
      }
    };

    const cfg = {
      type: type === 'por' ? 'bar' : 'line',
      data: config[type],
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            display: type !== 'geo' && type !== 'aidd',
            position: 'top',
            labels: {
              color: '#94A3B8',
              font: { size: 10, family: 'Inter' },
              boxWidth: 8,
              boxHeight: 8,
              usePointStyle: true,
              pointStyle: 'circle'
            }
          },
          tooltip: {
            backgroundColor: '#0A1F3D',
            borderColor: '#00D4FF',
            borderWidth: 1,
            titleColor: '#fff',
            bodyColor: '#94A3B8',
            padding: 10,
            cornerRadius: 6,
            titleFont: { size: 12, weight: 'bold' },
            bodyFont: { size: 11 }
          }
        },
        scales: {
          x: {
            grid: { color: 'rgba(255,255,255,0.04)', display: false },
            ticks: {
              color: '#94A3B8',
              font: { size: 10 }
            }
          },
          y: {
            grid: { color: 'rgba(255,255,255,0.06)' },
            ticks: {
              color: '#94A3B8',
              font: { size: 10 }
            }
          }
        }
      }
    };

    chartsInitialized[canvasId] = new Chart(ctx, cfg);
  }

  // 初始化默认显示的 GEO 图表
  setTimeout(() => initProductChart('geo'), 500);

  // ==========================================
  // 9. 弹窗管理
  // ==========================================
  window.openModal = function (type) {
    const modal = document.getElementById(type + 'Modal');
    if (modal) {
      modal.classList.add('show');
      document.body.style.overflow = 'hidden';
    }
  };

  window.closeModal = function (type) {
    const modal = document.getElementById(type + 'Modal');
    if (modal) {
      modal.classList.remove('show');
      document.body.style.overflow = '';
    }
  };

  // ESC 关闭弹窗
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      document.querySelectorAll('.modal.show').forEach(m => {
        m.classList.remove('show');
      });
      document.body.style.overflow = '';
    }
  });

  // ==========================================
  // 10. 表单提交（对接后端 API）
  // ==========================================
  const API_BASE = (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')
    ? window.location.protocol + '//' + window.location.hostname + ':9091'
    : '';

  function showToast(message, type = 'success') {
    const toast = document.getElementById('toast');
    const msgEl = toast.querySelector('.toast-msg');
    if (msgEl) msgEl.textContent = message;
    if (toast) {
      toast.classList.remove('success', 'error');
      toast.classList.add(type);
      toast.classList.add('show');
      setTimeout(() => toast.classList.remove('show'), 3500);
    }
  }

  // 报名字段 → 后端 DTO 映射
  function buildRegistrationPayload(form) {
    const fd = new FormData(form);
    const interested = [];
    form.querySelectorAll('input[name="product"]:checked').forEach(cb => interested.push(cb.value));
    const sel = form.querySelector('select[name="product"]');
    if (sel && sel.value && !interested.includes(sel.value)) interested.push(sel.value);
    return {
      name: (fd.get('name') || '').toString().trim(),
      company: (fd.get('company') || '').toString().trim(),
      position: (fd.get('position') || '').toString().trim() || null,
      phone: (fd.get('phone') || '').toString().trim(),
      email: (fd.get('email') || '').toString().trim(),
      interestedProducts: interested.length > 0 ? interested : ['GEO'],
      companySize: (fd.get('size') || '').toString().trim() || null,
      source: (fd.get('source') || '').toString().trim() || null,
      message: (fd.get('message') || '').toString().trim() || null
    };
  }

  async function submitTrial(form, modalType) {
    const submitBtn = form.querySelector('button[type="submit"]');
    const originalText = submitBtn.innerHTML;
    submitBtn.innerHTML = '提交中...';
    submitBtn.disabled = true;

    try {
      const payload = buildRegistrationPayload(form);
      const resp = await fetch(API_BASE + '/api/registrations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const json = await resp.json();
      if (json.success) {
        if (modalType) closeModal(modalType);
        showToast(json.message || '报名提交成功，顾问会在 24 小时内联系您', 'success');
        form.reset();
      } else {
        showToast(json.message || '提交失败，请检查输入', 'error');
      }
    } catch (err) {
      showToast('网络异常，请稍后再试', 'error');
      console.error(err);
    } finally {
      submitBtn.innerHTML = originalText;
      submitBtn.disabled = false;
    }
  }

  const trialForm = document.getElementById('trialForm');
  if (trialForm) trialForm.addEventListener('submit', e => { e.preventDefault(); submitTrial(trialForm); });

  const modalTrialForm = document.getElementById('modalTrialForm');
  if (modalTrialForm) modalTrialForm.addEventListener('submit', e => { e.preventDefault(); submitTrial(modalTrialForm, 'trial'); });

  const loginForm = document.getElementById('loginForm');
  if (loginForm) {
    loginForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      const submitBtn = loginForm.querySelector('button[type="submit"]');
      const originalText = submitBtn.innerHTML;
      submitBtn.innerHTML = '登录中...';
      submitBtn.disabled = true;

      try {
        const fd = new FormData(loginForm);
        const resp = await fetch(API_BASE + '/api/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: fd.get('username'),
            password: fd.get('password'),
            captchaId: loginForm.dataset.captchaId || '',
            captchaAnswer: fd.get('captchaAnswer') || ''
          })
        });
        const json = await resp.json();
        const payload = (json.code === 0 && json.data) ? json.data : null;
        // V2.0.66：验证码失败后自动刷新图形
        if (!payload && /验证码/.test(json.message || '')) {
          loadLoginCaptcha();
        }
        // V2.0.65：2FA challenge —— 密码正确但需要验证码（TOTP 6 位或备份码，即逃生通道）
        if (payload && payload.mfaRequired) {
          const tip = payload.mfaReason === 'MFA_REQUIRED'
            ? '该账号已启用两步验证'
            : '验证码错误，请重试';
          const code = prompt(`🔐 ${tip}\n\n请输入 Authenticator 中的 6 位验证码（或备份码）：`);
          if (!code) {
            showToast('已取消登录', 'error');
            submitBtn.innerHTML = originalText;
            submitBtn.disabled = false;
            return;
          }
          submitBtn.innerHTML = '验证中...';
          // V2.0.66：重试凭 captchaTicket（密码+图形验证码已验过），无需重复取验证码
          const r2 = await fetch(API_BASE + '/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              username: fd.get('username'),
              password: fd.get('password'),
              captchaTicket: payload.captchaTicket || '',
              totpCode: code.trim()
            })
          });
          const j2 = await r2.json().catch(() => ({}));
          const p2 = (j2.code === 0 && j2.data && !j2.data.mfaRequired) ? j2.data : null;
          if (!p2) {
            showToast((j2.data && j2.data.mfaRequired) ? '2FA 验证码错误' : (j2.message || '2FA 验证失败'), 'error');
            if (/验证码错误或已过期/.test(j2.message || '')) loadLoginCaptcha();   // V2.0.66：图形验证码过期自动刷新
            submitBtn.innerHTML = originalText;
            submitBtn.disabled = false;
            return;
          }
          Object.assign(payload, p2);   // 用挑战后的完整响应覆盖
        }
        if (payload && payload.token) {
          localStorage.setItem('lingyao_token', payload.token);
          // V2.0.21 P1.4：保存 refresh_token（30 天有效，用于静默续期）
          if (payload.refreshToken) {
            localStorage.setItem('lingyao_refresh_token', payload.refreshToken);
          }
          localStorage.setItem('lingyao_user', JSON.stringify(payload.user));
          closeModal('login');
          showToast('登录成功！欢迎 ' + (payload.user.displayName || payload.user.username), 'success');
          renderUserProducts(payload);
        } else {
          showToast(json.message || '登录失败', 'error');
        }
      } catch (err) {
        showToast('网络异常，请稍后再试', 'error');
      } finally {
        submitBtn.innerHTML = originalText;
        submitBtn.disabled = false;
      }
    });
  }

  // 登录后渲染产品列表（高亮/灰色）
  function renderUserProducts(loginData) {
    if (!loginData.products) return;
    loginData.products.forEach(p => {
      const tab = document.querySelector(`[data-product="${p.code}"]`);
      if (!tab) return;
      if (p.granted) {
        tab.classList.add('product-granted');
        tab.classList.remove('product-disabled');
      } else {
        tab.classList.add('product-disabled');
        tab.classList.remove('product-granted');
      }
    });
  }

  // ═══ V2.0.66：登录图形验证码（算术题 SVG，2 分钟有效，一次性）═══
  async function loadLoginCaptcha() {
    const form = document.getElementById('loginForm');
    if (!form) return;
    const img = document.getElementById('captchaImg');
    const input = document.getElementById('captchaAnswer');
    try {
      const res = await fetch(API_BASE + '/api/auth/captcha');
      const json = await res.json();
      if (json.code === 0 && json.data) {
        form.dataset.captchaId = json.data.captchaId;
        if (img) img.innerHTML = json.data.svg;
        if (input) input.value = '';
      }
    } catch (_) { /* 验证码加载失败不阻塞登录表单显示 */ }
  }
  window.loadLoginCaptcha = loadLoginCaptcha;

  const captchaImg = document.getElementById('captchaImg');
  if (captchaImg) {
    captchaImg.addEventListener('click', loadLoginCaptcha);
    captchaImg.style.cursor = 'pointer';
    captchaImg.title = '点击刷新验证码';
    loadLoginCaptcha();
  }

  // 退出登录 — V2.0.20 P1 阶段 A
  // 调后端 /api/auth/logout（服务端黑名单），再清前端状态
  function logout() {
    const token = localStorage.getItem('lingyao_token');
    // 1) 先发服务端黑名单（fire-and-forget，失败也继续清前端）
    if (token) {
      try {
        // 同步 fetch（保证后端 jti 写入黑名单后再清前端）
        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/auth/logout', false);  // 同步
        xhr.setRequestHeader('Authorization', 'Bearer ' + token);
        xhr.setRequestHeader('Content-Type', 'application/json');
        xhr.send('{}');
        // 不管成功 200 / 已过期 / 网络失败，都继续清前端
      } catch (e) {
        // 静默吞掉：网络失败也要清前端，避免用户登不出去
        console.warn('logout 后端调用失败（不影响前端清理）:', e);
      }
    }
    // 2) 清前端状态
    localStorage.removeItem('lingyao_token');
    localStorage.removeItem('lingyao_refresh_token');
    localStorage.removeItem('lingyao_user');
    localStorage.removeItem('lingyao_company');
    localStorage.removeItem('lingyao_products');
    // 3) 跳转登录页（而非 reload，避免循环）
    window.location.href = '/index.html';
  }
  window.lingyaoLogout = logout;

  // ══════════════════════════════════════════════════════════════════
  // V2.0.21 P1.4：refresh_token 静默续期
  // - 在 token 即将过期时（或 API 返回 401 时）调 /api/auth/refresh
  // - 成功后更新 localStorage 中的 token + refresh_token
  // - 失败（refresh 也过期）→ 跳登录页
  // ══════════════════════════════════════════════════════════════════

  // 同步锁：避免并发 401 同时触发多次 refresh
  let refreshingPromise = null;

  async function silentRefresh() {
    if (refreshingPromise) return refreshingPromise;
    const refreshToken = localStorage.getItem('lingyao_refresh_token');
    if (!refreshToken) {
      // 无 refresh_token → 跳登录
      window.location.href = '/index.html';
      return null;
    }
    refreshingPromise = (async () => {
      try {
        const resp = await fetch(API_BASE + '/api/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: refreshToken })
        });
        const json = await resp.json();
        if (json.success) {
          localStorage.setItem('lingyao_token', json.data.token);
          if (json.data.refreshToken) {
            localStorage.setItem('lingyao_refresh_token', json.data.refreshToken);
          }
          return json.data.token;
        } else {
          // refresh 失败 → 清状态 + 跳登录
          logout();
          return null;
        }
      } catch (e) {
        console.warn('silent refresh failed:', e);
        logout();
        return null;
      } finally {
        refreshingPromise = null;
      }
    })();
    return refreshingPromise;
  }
  window.lingyaoSilentRefresh = silentRefresh;

  // 包装 fetch：401 时自动 silent refresh + 重试一次
  const originalFetch = window.fetch.bind(window);
  window.fetch = async function(input, init) {
    let initWithCsrf = addCsrfHeader(input, init);
    let resp = await originalFetch(input, initWithCsrf);

    // 仅对带 Authorization 的请求做 401 自动续期
    const auth = (initWithCsrf && initWithCsrf.headers && initWithCsrf.headers.Authorization) || '';
    if (resp.status === 401 && auth.startsWith('Bearer ')) {
      const url = typeof input === 'string' ? input : (input.url || '');
      // 不对 /api/auth/login 和 /api/auth/refresh 自身做续期（避免循环）
      if (url.includes('/api/auth/login') || url.includes('/api/auth/refresh')) {
        return resp;
      }
      // 尝试续期
      const newToken = await silentRefresh();
      if (!newToken) return resp;

      // 重发原请求（替换 token + 重新注入 CSRF）
      const newInit = { ...init };
      newInit.headers = { ...(init.headers || {}) };
      // 替换 Authorization 头中的 Bearer xxx
      newInit.headers.Authorization = 'Bearer ' + newToken;
      // 401 续期后 cookie 可能已重新生成，重新读一次
      const newInitWithCsrf = addCsrfHeader(input, newInit);
      resp = await originalFetch(input, newInitWithCsrf);
    }
    return resp;
  };

  // ══════════════════════════════════════════════════════════════════
  // V2.0.25 P2.3：CSRF 防护（Double-Submit Cookie 模式）
  // - 读 XSRF-TOKEN cookie → 注入到 X-XSRF-TOKEN header
  // - 状态变更方法（POST/PUT/DELETE/PATCH）才需要
  // - 与 X-XSRF-TOKEN 头名对应，服务端 CsrfFilter 校验
  // ══════════════════════════════════════════════════════════════════

  const CSRF_COOKIE_NAME = 'XSRF-TOKEN';
  const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';
  const CSRF_METHODS = ['POST', 'PUT', 'DELETE', 'PATCH'];

  /**
   * 读 cookie 值的通用工具（document.cookie 解析）
   */
  function readCookie(name) {
    const match = document.cookie.match(new RegExp('(^|; )' + name + '=([^;]*)'));
    return match ? decodeURIComponent(match[2]) : null;
  }

  /**
   * 给 fetch init 注入 X-XSRF-TOKEN header（如果方法需要）
   * @returns 新的 init 对象（不修改原对象）
   */
  function addCsrfHeader(input, init) {
    if (!init) init = {};
    const method = (init.method || (typeof input === 'object' && input.method) || 'GET').toUpperCase();
    if (!CSRF_METHODS.includes(method)) {
      // 幂等方法（GET/HEAD/OPTIONS）不需要 CSRF
      return init;
    }
    // 公开端点不注入（与后端 EXEMPT 列表对齐，避免误导）
    const url = typeof input === 'string' ? input : (input.url || '');
    if (isCsrfExemptUrl(url)) {
      return init;
    }
    const csrfToken = readCookie(CSRF_COOKIE_NAME);
    if (!csrfToken) {
      // 没 cookie：让后端拒绝（403），前端会在控制台打 warning
      console.warn('CSRF token cookie 缺失，请先访问任意 GET 接口获取');
      return init;
    }
    // 拷贝 headers（避免污染原对象）
    const newHeaders = { ...(init.headers || {}) };
    newHeaders[CSRF_HEADER_NAME] = csrfToken;
    return { ...init, headers: newHeaders };
  }

  /**
   * CSRF 豁免路径判断（与后端 CsrfFilter.EXEMPT_* 对齐）
   */
  function isCsrfExemptUrl(url) {
    if (!url) return false;
    const EXACT = [
      '/api/auth/login', '/api/auth/logout', '/api/auth/refresh',
      '/api/auth/forgot-password', '/api/auth/reset-password',
      '/api/auth/register', '/api/registrations', '/api/registrations/stats',
      '/api/feedback', '/api/health', '/api/version', '/api/_diag/version'
    ];
    const PREFIXES = ['/api/public/', '/api/invitations/', '/api/sub-product/', '/api/_diag/', '/h2-console/'];
    for (const e of EXACT) if (url.includes(e)) return true;
    for (const p of PREFIXES) if (url.includes(p)) return true;
    return false;
  }

  // 导出给测试或调试用
  window.lingyaoCsrfToken = () => readCookie(CSRF_COOKIE_NAME);

  // ==========================================
  // 11. 平滑滚动锚点
  // ==========================================
  document.querySelectorAll('a[href^="#"]').forEach(link => {
    link.addEventListener('click', (e) => {
      const targetId = link.getAttribute('href');
      if (targetId.length > 1) {
        const target = document.querySelector(targetId);
        if (target) {
          e.preventDefault();
          window.scrollTo({
            top: target.offsetTop - 80,
            behavior: 'smooth'
          });
        }
      }
    });
  });

  // ==========================================
  // 12. 当前激活的导航链接高亮
  // ==========================================
  const sections = document.querySelectorAll('section[id]');
  const navLinks = document.querySelectorAll('.nav-link');

  function updateActiveLink() {
    const scrollPos = window.pageYOffset + 120;

    sections.forEach(section => {
      const top = section.offsetTop;
      const bottom = top + section.offsetHeight;
      const id = section.getAttribute('id');

      if (scrollPos >= top && scrollPos < bottom) {
        navLinks.forEach(link => {
          link.style.color = '';
          if (link.getAttribute('href') === '#' + id) {
            link.style.color = 'var(--text-bright)';
          }
        });
      }
    });
  }
  window.addEventListener('scroll', updateActiveLink, { passive: true });

  // ==========================================
  // 13. 控制台彩蛋
  // ==========================================
  console.log(
    '%c凌瑶智数 LingYao Digital',
    'color: #00D4FF; font-size: 22px; font-weight: bold;'
  );
  console.log(
    '%c面向医药企业的垂直化软件服务商',
    'color: #94A3B8; font-size: 13px;'
  );
  console.log(
    '%c让医药企业从经验判断走向数据智能 · 2026',
    'color: #7B61FF; font-size: 11px;'
  );

})();
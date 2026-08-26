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
    ? 'http://127.0.0.1:9091'
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
    return {
      name: (fd.get('name') || '').toString().trim(),
      company: (fd.get('company') || '').toString().trim(),
      position: (fd.get('position') || '').toString().trim() || null,
      phone: (fd.get('phone') || '').toString().trim(),
      email: (fd.get('email') || '').toString().trim(),
      interestedProducts: interested.length > 0 ? interested : ['geo-monitor'],
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
            password: fd.get('password')
          })
        });
        const json = await resp.json();
        if (json.success) {
          localStorage.setItem('lingyao_token', json.data.token);
          localStorage.setItem('lingyao_user', JSON.stringify(json.data.user));
          closeModal('login');
          showToast('登录成功！欢迎 ' + (json.data.user.displayName || json.data.user.username), 'success');
          renderUserProducts(json.data);
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

  // 退出登录
  function logout() {
    localStorage.removeItem('lingyao_token');
    localStorage.removeItem('lingyao_user');
    location.reload();
  }
  window.lingyaoLogout = logout;

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
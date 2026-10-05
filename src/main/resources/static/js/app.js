// Scoopy front-end behaviour (plain JavaScript, no libraries).
(() => {
  const $ = (sel, root = document) => root.querySelector(sel);
  const rupee = n => '₹' + n;

  // --- Toast message ---------------------------------------------------
  let toast;
  function showToast(msg) {
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'toast';
      toast.setAttribute('role', 'status');
      document.body.appendChild(toast);
    }
    toast.textContent = msg;
    toast.classList.add('show');
    clearTimeout(showToast.t);
    showToast.t = setTimeout(() => toast.classList.remove('show'), 1800);
  }

  // --- Cart badge in the header ---------------------------------------
  function setBadge(n) {
    const b = $('#cart-badge');
    if (!b) return;
    b.textContent = n;
    b.hidden = n === 0;
    b.classList.remove('pop');
    void b.offsetWidth; // restart animation
    b.classList.add('pop');
  }

  // --- Add / change quantity without reloading the page ---------------
  document.addEventListener('submit', async e => {
    const form = e.target.closest('form.js-cart');
    if (!form) return;
    e.preventDefault();
    const data = new URLSearchParams(new FormData(form, e.submitter));
    try {
      const res = await fetch(form.action.replace('/cart/change/', '/api/cart/change/'), {
        method: 'POST', body: data
      });
      if (!res.ok) throw new Error(res.status);
      const d = await res.json();
      setBadge(d.count);

      const row = form.closest('.row');
      if (!row) { showToast('Added to cart: ' + (form.dataset.name || 'item')); return; }

      // Cart page: update this row and the price summary.
      if (d.count === 0) { location.reload(); return; }
      if (d.qty === 0) row.remove();
      else { $('.qv', row).textContent = d.qty; $('.sub', row).textContent = rupee(d.lineTotal); }
      $('#s-count').textContent = d.count;
      $('#s-sub').textContent = rupee(d.subtotal);
      $('#s-del').textContent = d.delivery === 0 ? 'Free' : rupee(d.delivery);
      $('#s-tot').textContent = rupee(d.total);
    } catch (err) {
      showToast('Could not update the cart. Please try again.');
    }
  });

  // --- Live search: filter products while typing ----------------------
  const grid = $('.grid'), search = $('.search input');
  if (grid && search) {
    const cards = [...grid.children];
    const none = document.createElement('p');
    none.className = 'empty';
    none.hidden = true;
    none.textContent = 'No flavours match your search. Try another name.';
    grid.after(none);
    search.addEventListener('input', () => {
      const q = search.value.trim().toLowerCase();
      let shown = 0;
      cards.forEach(c => {
        const hit = !q || c.textContent.toLowerCase().includes(q);
        c.hidden = !hit;
        if (hit) shown++;
      });
      none.hidden = shown > 0;
    });
  }

  // --- Sort dropdown submits itself -----------------------------------
  document.querySelectorAll('select[data-autosubmit]').forEach(s =>
    s.addEventListener('change', () => s.form.submit()));

  // --- Checkout form: digits-only phone, block double submit ----------
  const phone = $('input[name=phone]');
  if (phone) phone.addEventListener('input', () => {
    phone.value = phone.value.replace(/\D/g, '').slice(0, 10);
  });
  const checkout = $('form.addr');
  if (checkout) checkout.addEventListener('submit', () => {
    const btn = $('button', checkout);
    btn.disabled = true;
    btn.textContent = 'Placing order…';
  });
})();

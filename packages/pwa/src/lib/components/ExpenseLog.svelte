<script lang="ts">
  /**
   * <ExpenseLog />
   *
   * User-wide Expense log. Maps to records.type='expense' with
   * structured fields in metadata mirroring the Palm Expense app
   * schema (amount, currency, vendor, type, payment, date, city,
   * attendees, notes).
   *
   * records.body holds the vendor as a display-anchor so list
   * sorts/searches don't have to crack metadata.
   */
  import { authState } from '$lib/auth.svelte';
  import { sync } from '$lib/sync.svelte';
  import {
    listExpenses,
    createExpense as createExpenseStore,
    updateExpense as updateExpenseStore,
    deleteExpense as deleteExpenseStore,
  } from '$lib/stores/expenses.svelte';
  import { t } from '$lib/i18n.svelte';
  import { palmConfirm } from '$lib/confirm.svelte';

  // Palm Expense defaults
  const EXPENSE_TYPES = [
    'Airfare', 'Breakfast', 'Bus', 'Business Meals', 'Car Rental',
    'Dinner', 'Entertainment', 'Fax', 'Gas', 'Gifts',
    'Hotel', 'Incidentals', 'Laundry', 'Limo', 'Lodging',
    'Lunch', 'Mileage', 'Other', 'Parking', 'Postage',
    'Snack', 'Subway', 'Supplies', 'Taxi', 'Telephone',
    'Tips', 'Tolls', 'Train',
  ];
  const PAYMENT_TYPES = [
    'American Express', 'Cash', 'Check', 'Credit Card',
    'MasterCard', 'Prepaid', 'VISA', 'Unfiled',
  ];

  interface ExpenseMeta {
    palm_amount?: number;
    palm_currency?: string;
    palm_vendor?: string;
    palm_expense_type?: string;
    palm_payment?: string;
    palm_expense_date?: string;
    palm_city?: string;
    palm_attendees?: string;
    palm_notes?: string;
    palm_category_name?: string;
  }

  interface Expense {
    id: string;
    user_id: string;
    type: 'expense';
    body: string;
    metadata: ExpenseMeta | null;
    created_at: string;
    updated_at: string;
  }

  let expenses = $state<Expense[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let search = $state('');

  let showForm = $state(false);
  let editingId = $state<string | null>(null);

  let fVendor = $state('');
  let fAmount = $state<string>(''); // string for input precision
  let fCurrency = $state('USD');
  let fExpenseType = $state('Other');
  let fPayment = $state('Cash');
  let fDate = $state('');
  let fCity = $state('');
  let fAttendees = $state('');
  let fNotes = $state('');
  let formBusy = $state(false);
  let formError = $state<string | null>(null);

  async function load() {
    if (!authState.userId) return;
    loading = true;
    loadError = null;
    try {
      const data = await listExpenses();
      expenses = data as unknown as Expense[];
    } catch (e) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  function resetForm() {
    fVendor = '';
    fAmount = '';
    fCurrency = 'USD';
    fExpenseType = 'Other';
    fPayment = 'Cash';
    fDate = new Date().toISOString().slice(0, 10);
    fCity = '';
    fAttendees = '';
    fNotes = '';
    formError = null;
    editingId = null;
  }

  function openNew() {
    resetForm();
    fDate = new Date().toISOString().slice(0, 10);
    showForm = true;
  }
  function openEdit(e: Expense) {
    const m = e.metadata ?? {};
    editingId = e.id;
    fVendor = m.palm_vendor ?? e.body;
    fAmount = m.palm_amount != null ? String(m.palm_amount) : '';
    fCurrency = m.palm_currency ?? 'USD';
    fExpenseType = m.palm_expense_type ?? 'Other';
    fPayment = m.palm_payment ?? 'Cash';
    fDate = m.palm_expense_date ?? '';
    fCity = m.palm_city ?? '';
    fAttendees = m.palm_attendees ?? '';
    fNotes = m.palm_notes ?? '';
    formError = null;
    showForm = true;
  }
  function closeForm() {
    showForm = false;
    resetForm();
  }

  async function submit() {
    if (!authState.userId) return;
    if (!fVendor.trim() && fAmount === '') {
      formError = 'vendor or amount required';
      return;
    }
    formBusy = true;
    formError = null;
    const amt = fAmount.trim() === '' ? undefined : Number.parseFloat(fAmount);
    if (amt != null && Number.isNaN(amt)) {
      formError = 'amount must be a number';
      formBusy = false;
      return;
    }
    const meta: ExpenseMeta = {
      palm_amount: amt,
      palm_currency: fCurrency,
      palm_vendor: fVendor.trim() || undefined,
      palm_expense_type: fExpenseType,
      palm_payment: fPayment,
      palm_expense_date: fDate || undefined,
      palm_city: fCity.trim() || undefined,
      palm_attendees: fAttendees.trim() || undefined,
      palm_notes: fNotes.trim() || undefined,
      palm_category_name: 'Unfiled',
    };
    const body = fVendor.trim() || `${fExpenseType} ${amt ?? ''}`.trim();

    if (editingId) {
      try {
        await updateExpenseStore(editingId, { body, metadata: meta });
      } catch (err) {
        formBusy = false;
        formError = err instanceof Error ? err.message : String(err);
        return;
      }
    } else {
      try {
        await createExpenseStore({ vendor: body, metadata: meta });
      } catch (err) {
        formBusy = false;
        formError = err instanceof Error ? err.message : String(err);
        return;
      }
    }
    formBusy = false;
    closeForm();
    await load();
  }

  async function deleteExpense(e: Expense) {
    if (!(await palmConfirm(`Delete expense "${e.body}"?`, { danger: true }))) return;
    try {
      await deleteExpenseStore(e.id);
    } catch (err) {
      alert(err instanceof Error ? err.message : String(err));
      return;
    }
    if (editingId === e.id) closeForm();
    await load();
  }

  function fmtAmount(e: Expense): string {
    const a = e.metadata?.palm_amount;
    const c = e.metadata?.palm_currency ?? 'USD';
    if (a == null) return '—';
    return `${c} ${a.toFixed(2)}`;
  }

  function fmtDate(s: string | undefined): string {
    if (!s) return '';
    return new Date(s + 'T00:00:00').toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  }

  const filtered = $derived(
    search.trim() === ''
      ? expenses
      : expenses.filter((e) => {
          const q = search.toLowerCase();
          if (e.body.toLowerCase().includes(q)) return true;
          const m = e.metadata ?? {};
          if ((m.palm_expense_type ?? '').toLowerCase().includes(q)) return true;
          if ((m.palm_city ?? '').toLowerCase().includes(q)) return true;
          if ((m.palm_attendees ?? '').toLowerCase().includes(q)) return true;
          return false;
        }),
  );

  // Totals per currency
  const totals = $derived.by(() => {
    const map = new Map<string, number>();
    for (const e of expenses) {
      const c = e.metadata?.palm_currency ?? 'USD';
      const a = e.metadata?.palm_amount ?? 0;
      map.set(c, (map.get(c) ?? 0) + a);
    }
    return Array.from(map.entries()).sort();
  });

  $effect(() => {
    if (authState.phase === 'ready') void load();
  });

  // Re-render whenever the sync engine finishes a pull from the
  // server (which also fires on offline → online transitions). The
  // local Dexie store is the source of truth.
  $effect(() => {
    sync.last_pulled_at; // touched for reactivity
    void load();
  });
</script>

<section class="expenselog">
  <header class="head">
    <input class="search" type="search" bind:value={search} placeholder={t('expense.searchPh')} />
    <button class="add" onclick={openNew}>{t('expense.newExpense')}</button>
  </header>

  {#if totals.length > 0}
    <div class="totals">
      <span class="label">{t('expense.total')}</span>
      {#each totals as [cur, sum] (cur)}
        <span class="total-chip">{cur} {sum.toFixed(2)}</span>
      {/each}
    </div>
  {/if}

  {#if showForm}
    <form class="form" onsubmit={(e) => { e.preventDefault(); void submit(); }}>
      <header class="form-h">
        <h3>{editingId ? 'edit expense' : 'new expense'}</h3>
        <button type="button" class="link" onclick={closeForm}>cancel</button>
      </header>

      <div class="row-2">
        <label><span>vendor</span><input bind:value={fVendor} maxlength="128" /></label>
        <label><span>date</span><input type="date" bind:value={fDate} /></label>
      </div>
      <div class="row-3">
        <label>
          <span>amount</span>
          <input type="number" step="0.01" min="0" bind:value={fAmount} />
        </label>
        <label>
          <span>currency</span>
          <input bind:value={fCurrency} maxlength="3" placeholder="USD" />
        </label>
        <label>
          <span>payment</span>
          <select bind:value={fPayment}>
            {#each PAYMENT_TYPES as p (p)}
              <option value={p}>{p}</option>
            {/each}
          </select>
        </label>
      </div>
      <div class="row-2">
        <label>
          <span>type</span>
          <select bind:value={fExpenseType}>
            {#each EXPENSE_TYPES as t (t)}
              <option value={t}>{t}</option>
            {/each}
          </select>
        </label>
        <label><span>city</span><input bind:value={fCity} maxlength="64" /></label>
      </div>
      <label><span>attendees</span><input bind:value={fAttendees} maxlength="256" /></label>
      <label><span>notes</span><textarea bind:value={fNotes} rows="3" maxlength="2048"></textarea></label>

      <div class="form-actions">
        {#if formError}<span class="error">{formError}</span>{/if}
        <button class="primary" type="submit" disabled={formBusy}>
          {formBusy ? 'saving…' : editingId ? 'save' : 'add'}
        </button>
      </div>
    </form>
  {/if}

  {#if loading}
    <p class="status">loading…</p>
  {:else if loadError}
    <p class="status error">{loadError}</p>
  {:else if filtered.length === 0}
    <p class="status">
      {expenses.length === 0
        ? 'No expenses yet. Add one above or push from Expense via vellum-sync.'
        : 'No matches.'}
    </p>
  {:else}
    <ul class="list">
      {#each filtered as e (e.id)}
        <li class="item">
          <button class="card" type="button" onclick={() => openEdit(e)}>
            <span class="vendor">{e.body}</span>
            <span class="amt">{fmtAmount(e)}</span>
            <span class="meta">
              {e.metadata?.palm_expense_type ?? ''}
              {#if e.metadata?.palm_expense_date}<span class="sep">·</span>{fmtDate(e.metadata.palm_expense_date)}{/if}
              {#if e.metadata?.palm_payment}<span class="sep">·</span>{e.metadata.palm_payment}{/if}
              {#if e.metadata?.palm_city}<span class="sep">·</span>{e.metadata.palm_city}{/if}
            </span>
            {#if e.metadata?.palm_attendees}<span class="notes">w/ {e.metadata.palm_attendees}</span>{/if}
          </button>
          <button class="del" onclick={() => deleteExpense(e)}>{t('common.delete')}</button>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .expenselog {
    max-width: 820px;
  }
  .head {
    display: flex;
    gap: 0.6rem;
    margin-bottom: 0.8rem;
  }
  .search {
    flex: 1;
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.45rem 0.7rem;
    font: inherit;
    font-size: 0.9rem;
  }
  .add,
  .primary {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.4rem 0.9rem;
    font: inherit;
    font-weight: 600;
    cursor: pointer;
    white-space: nowrap;
  }
  .add:hover,
  .primary:hover:not(:disabled) {
    background: var(--accent-dim);
  }
  .primary:disabled {
    opacity: 0.6;
  }
  .totals {
    display: flex;
    gap: 0.4rem;
    align-items: center;
    margin-bottom: 1rem;
    flex-wrap: wrap;
  }
  .label {
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .total-chip {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 0.2rem 0.55rem;
    font-size: 0.85rem;
    color: var(--ink);
  }

  .form {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1rem 1.1rem;
    margin-bottom: 1rem;
    display: grid;
    gap: 0.7rem;
    border-radius: 2px;
  }
  .form-h {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
  }
  .form-h h3 {
    margin: 0;
    font-size: 0.95rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .form label {
    display: grid;
    gap: 0.2rem;
    font-size: 0.8rem;
    color: var(--ink-mute);
  }
  .form input,
  .form select,
  .form textarea {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.4rem 0.55rem;
    font: inherit;
    font-size: 0.9rem;
  }
  .row-2 {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.7rem;
  }
  .row-3 {
    display: grid;
    grid-template-columns: 1fr 1fr 1fr;
    gap: 0.7rem;
  }
  @media (max-width: 600px) {
    .row-2,
    .row-3 {
      grid-template-columns: 1fr;
    }
    .head {
      flex-wrap: wrap;
    }
    .search {
      flex: 1 1 100%;
      order: -1;
    }
    .card {
      grid-template-columns: 1fr;
      gap: 0.15rem;
    }
    .amt {
      grid-row: auto;
      grid-column: 1;
      justify-self: start;
    }
    .totals {
      font-size: 0.8rem;
    }
  }
  .form-actions {
    display: flex;
    align-items: center;
    gap: 0.7rem;
    justify-content: flex-end;
  }
  .form-actions .error {
    margin-right: auto;
  }

  .status {
    color: var(--ink-mute);
    padding: 2rem 1rem;
    text-align: center;
  }
  .status.error,
  .error {
    color: #ff6b6b;
    font-size: 0.85rem;
  }

  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.4rem;
  }
  .item {
    display: flex;
    align-items: stretch;
    gap: 0.2rem;
  }
  .card {
    flex: 1;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.55rem 0.85rem;
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 0.15rem 0.8rem;
    text-align: left;
    cursor: pointer;
    font: inherit;
    border-radius: 2px;
    align-items: baseline;
  }
  .card:hover {
    border-color: var(--accent);
  }
  .vendor {
    font-weight: 600;
  }
  .amt {
    color: var(--cat-finance);
    font-weight: 600;
    grid-row: 1;
    grid-column: 2;
  }
  .card {
    border-left: 3px solid var(--cat-finance);
  }
  .meta {
    grid-column: 1 / span 2;
    font-size: 0.8rem;
    color: var(--ink-mute);
  }
  .notes {
    grid-column: 1 / span 2;
    font-size: 0.75rem;
    color: var(--ink-mute);
    font-style: italic;
  }
  .sep {
    color: var(--line);
    margin: 0 0.3rem;
  }
  /* Unified Delete style — text "delete" in red outline → red fill on
     press. Matches every organizer. */
  .del {
    background: transparent;
    border: 1px solid #c62828;
    color: #c62828;
    padding: 0 0.95rem;
    font: inherit;
    font-size: 0.88rem;
    font-weight: 700;
    line-height: 1;
    min-height: 40px;
    border-radius: 4px;
    cursor: pointer;
    text-transform: lowercase;
    letter-spacing: 0.02em;
  }
  .del:hover,
  .del:active {
    background: #c62828;
    color: #fff;
  }
  .link {
    background: none;
    border: none;
    color: var(--ink-mute);
    font: inherit;
    font-size: 0.85rem;
    cursor: pointer;
  }
  .link:hover {
    color: var(--accent);
  }
</style>

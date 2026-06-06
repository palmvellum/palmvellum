<script lang="ts">
  /**
   * <AddressBook />
   *
   * User-wide Address Book. Maps to records.type='contact' with
   * structured fields in metadata that mirror the Palm AddressDB
   * schema (first/last/company/title, up to five labelled phones
   * + email, postal address, notes, category).
   *
   * records.body holds a display-formatted name string so list
   * queries can sort/search without hitting metadata.
   */
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { newUlid } from '$lib/ulid';

  type PhoneType = 'Work' | 'Home' | 'Fax' | 'Other' | 'E-mail' | 'Main' | 'Pager' | 'Mobile';
  const PHONE_TYPES: PhoneType[] = [
    'Work', 'Home', 'Fax', 'Other', 'E-mail', 'Main', 'Pager', 'Mobile',
  ];

  interface PhoneEntry {
    label: PhoneType;
    value: string;
  }

  interface ContactMeta {
    palm_first_name?: string;
    palm_last_name?: string;
    palm_company?: string;
    palm_title?: string;
    palm_phones?: PhoneEntry[];
    palm_address?: string;
    palm_city?: string;
    palm_state?: string;
    palm_zip?: string;
    palm_country?: string;
    palm_notes?: string;
    palm_category_name?: string;
  }

  interface Contact {
    id: string;
    user_id: string;
    type: 'contact';
    body: string;
    metadata: ContactMeta | null;
    created_at: string;
    updated_at: string;
  }

  let contacts = $state<Contact[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let search = $state('');

  let showForm = $state(false);
  let editingId = $state<string | null>(null);

  // Form state
  let fFirst = $state('');
  let fLast = $state('');
  let fCompany = $state('');
  let fTitle = $state('');
  let fPhones = $state<PhoneEntry[]>([{ label: 'Work', value: '' }]);
  let fAddress = $state('');
  let fCity = $state('');
  let fState = $state('');
  let fZip = $state('');
  let fCountry = $state('');
  let fNotes = $state('');
  let formBusy = $state(false);
  let formError = $state<string | null>(null);

  async function load() {
    if (!authState.userId) return;
    loading = true;
    loadError = null;
    const { data, error } = await supabase
      .from('records')
      .select('*')
      .eq('type', 'contact')
      .is('deleted_at', null)
      .order('body', { ascending: true });
    loading = false;
    if (error) {
      loadError = error.message;
      return;
    }
    contacts = (data ?? []) as Contact[];
  }

  function resetForm() {
    fFirst = '';
    fLast = '';
    fCompany = '';
    fTitle = '';
    fPhones = [{ label: 'Work', value: '' }];
    fAddress = '';
    fCity = '';
    fState = '';
    fZip = '';
    fCountry = '';
    fNotes = '';
    formError = null;
    editingId = null;
  }

  function openNew() {
    resetForm();
    showForm = true;
  }

  function openEdit(c: Contact) {
    const m = c.metadata ?? {};
    editingId = c.id;
    fFirst = m.palm_first_name ?? '';
    fLast = m.palm_last_name ?? '';
    fCompany = m.palm_company ?? '';
    fTitle = m.palm_title ?? '';
    fPhones =
      m.palm_phones && m.palm_phones.length > 0
        ? m.palm_phones.map((p) => ({ ...p }))
        : [{ label: 'Work', value: '' }];
    fAddress = m.palm_address ?? '';
    fCity = m.palm_city ?? '';
    fState = m.palm_state ?? '';
    fZip = m.palm_zip ?? '';
    fCountry = m.palm_country ?? '';
    fNotes = m.palm_notes ?? '';
    formError = null;
    showForm = true;
  }

  function closeForm() {
    showForm = false;
    resetForm();
  }

  function addPhone() {
    if (fPhones.length >= 5) return;
    fPhones = [...fPhones, { label: 'Other', value: '' }];
  }
  function removePhone(i: number) {
    fPhones = fPhones.filter((_, idx) => idx !== i);
    if (fPhones.length === 0) fPhones = [{ label: 'Work', value: '' }];
  }

  function displayName(): string {
    const last = fLast.trim();
    const first = fFirst.trim();
    if (last && first) return `${last}, ${first}`;
    if (last) return last;
    if (first) return first;
    if (fCompany.trim()) return fCompany.trim();
    return '(unnamed)';
  }

  async function submit() {
    if (!authState.userId) return;
    if (!fFirst.trim() && !fLast.trim() && !fCompany.trim()) {
      formError = 'at least one of first name / last name / company';
      return;
    }
    formBusy = true;
    formError = null;
    const trimmedPhones = fPhones
      .map((p) => ({ label: p.label, value: p.value.trim() }))
      .filter((p) => p.value !== '');
    const meta: ContactMeta = {
      palm_first_name: fFirst.trim() || undefined,
      palm_last_name: fLast.trim() || undefined,
      palm_company: fCompany.trim() || undefined,
      palm_title: fTitle.trim() || undefined,
      palm_phones: trimmedPhones,
      palm_address: fAddress.trim() || undefined,
      palm_city: fCity.trim() || undefined,
      palm_state: fState.trim() || undefined,
      palm_zip: fZip.trim() || undefined,
      palm_country: fCountry.trim() || undefined,
      palm_notes: fNotes.trim() || undefined,
      palm_category_name: 'Unfiled',
    };
    const body = displayName();

    if (editingId) {
      const { error } = await supabase
        .from('records')
        .update({ body, metadata: meta })
        .eq('id', editingId);
      formBusy = false;
      if (error) {
        formError = error.message;
        return;
      }
    } else {
      const { error } = await supabase.from('records').insert({
        id: newUlid(),
        user_id: authState.userId,
        type: 'contact',
        posture: 'open',
        body,
        source: 'web',
        metadata: meta,
      });
      formBusy = false;
      if (error) {
        formError = error.message;
        return;
      }
    }
    closeForm();
    await load();
  }

  async function deleteContact(c: Contact) {
    if (!confirm(`Delete contact "${c.body}"?`)) return;
    const { error } = await supabase
      .from('records')
      .update({ deleted_at: new Date().toISOString() })
      .eq('id', c.id);
    if (error) {
      alert(error.message);
      return;
    }
    if (editingId === c.id) closeForm();
    await load();
  }

  function primaryPhone(c: Contact): string {
    const phones = c.metadata?.palm_phones ?? [];
    if (phones.length === 0) return '';
    return `${phones[0]!.label}: ${phones[0]!.value}`;
  }

  const filtered = $derived(
    search.trim() === ''
      ? contacts
      : contacts.filter((c) => {
          const q = search.toLowerCase();
          if (c.body.toLowerCase().includes(q)) return true;
          const m = c.metadata ?? {};
          if ((m.palm_company ?? '').toLowerCase().includes(q)) return true;
          if ((m.palm_phones ?? []).some((p) => p.value.toLowerCase().includes(q))) return true;
          return false;
        }),
  );

  $effect(() => {
    if (authState.phase === 'ready') void load();
  });

  $effect(() => {
    const channel = supabase
      .channel('contact-all')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'records' },
        async (payload) => {
          const row = (payload.new ?? payload.old) as { type?: string };
          if (row?.type !== 'contact') return;
          await load();
        },
      )
      .subscribe();
    return () => {
      channel.unsubscribe();
    };
  });
</script>

<section class="addressbook">
  <header class="head">
    <input
      class="search"
      type="search"
      bind:value={search}
      placeholder="search by name / company / phone…"
    />
    <button class="add" onclick={openNew}>+ new contact</button>
  </header>

  {#if showForm}
    <form class="form" onsubmit={(e) => { e.preventDefault(); void submit(); }}>
      <header class="form-h">
        <h3>{editingId ? 'edit contact' : 'new contact'}</h3>
        <button type="button" class="link" onclick={closeForm}>cancel</button>
      </header>
      <div class="row-2">
        <label><span>first name</span><input bind:value={fFirst} maxlength="64" /></label>
        <label><span>last name</span><input bind:value={fLast} maxlength="64" /></label>
      </div>
      <div class="row-2">
        <label><span>company</span><input bind:value={fCompany} maxlength="128" /></label>
        <label><span>title</span><input bind:value={fTitle} maxlength="128" /></label>
      </div>

      <fieldset class="phones">
        <legend>phones / emails</legend>
        {#each fPhones as p, i (i)}
          <div class="phone-row">
            <select bind:value={p.label}>
              {#each PHONE_TYPES as pt (pt)}
                <option value={pt}>{pt}</option>
              {/each}
            </select>
            <input bind:value={p.value} maxlength="128" placeholder="number or email" />
            <button type="button" class="link danger" onclick={() => removePhone(i)} aria-label="remove">×</button>
          </div>
        {/each}
        {#if fPhones.length < 5}
          <button type="button" class="link" onclick={addPhone}>+ add phone/email</button>
        {/if}
      </fieldset>

      <label><span>address</span><input bind:value={fAddress} maxlength="256" /></label>
      <div class="row-3">
        <label><span>city</span><input bind:value={fCity} maxlength="64" /></label>
        <label><span>state</span><input bind:value={fState} maxlength="64" /></label>
        <label><span>zip</span><input bind:value={fZip} maxlength="32" /></label>
      </div>
      <label><span>country</span><input bind:value={fCountry} maxlength="64" /></label>
      <label>
        <span>notes</span>
        <textarea bind:value={fNotes} rows="3" maxlength="2048"></textarea>
      </label>

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
      {contacts.length === 0
        ? 'No contacts yet. Add one above or push from Address Book via vellum-sync.'
        : 'No matches.'}
    </p>
  {:else}
    <ul class="list">
      {#each filtered as c (c.id)}
        <li class="contact">
          <button class="card" type="button" onclick={() => openEdit(c)}>
            <span class="name">{c.body}</span>
            {#if c.metadata?.palm_company}
              <span class="company">{c.metadata.palm_company}{c.metadata.palm_title ? ` — ${c.metadata.palm_title}` : ''}</span>
            {/if}
            {#if primaryPhone(c)}
              <span class="phone">{primaryPhone(c)}</span>
            {/if}
          </button>
          <button class="link danger del" onclick={() => deleteContact(c)} aria-label="delete">×</button>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .addressbook {
    max-width: 820px;
  }
  .head {
    display: flex;
    gap: 0.6rem;
    margin-bottom: 1rem;
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
    grid-template-columns: 2fr 1fr 1fr;
    gap: 0.7rem;
  }
  @media (max-width: 600px) {
    .row-2,
    .row-3 {
      grid-template-columns: 1fr;
    }
    .phone-row {
      grid-template-columns: 1fr auto !important;
    }
    .phone-row select {
      grid-column: 1;
    }
    .phone-row input {
      grid-column: 1 / -1;
      grid-row: 2;
    }
    .phone-row button {
      grid-column: 2;
      grid-row: 1;
      align-self: start;
    }
    .head {
      flex-wrap: wrap;
    }
    .search {
      flex: 1 1 100%;
      order: -1;
    }
  }
  fieldset.phones {
    border: 1px solid var(--line);
    padding: 0.6rem 0.8rem;
    margin: 0;
  }
  fieldset.phones legend {
    color: var(--ink-mute);
    font-size: 0.78rem;
    padding: 0 0.3rem;
  }
  .phone-row {
    display: grid;
    grid-template-columns: 100px 1fr auto;
    gap: 0.4rem;
    align-items: center;
    margin-bottom: 0.4rem;
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
  .contact {
    display: flex;
    align-items: stretch;
    gap: 0.2rem;
  }
  .card {
    flex: 1;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    border-left: 3px solid var(--cat-contact);
    color: var(--ink);
    padding: 0.6rem 0.9rem;
    display: grid;
    gap: 0.15rem;
    text-align: left;
    cursor: pointer;
    font: inherit;
    border-radius: 2px;
  }
  .card:hover {
    border-color: var(--accent);
  }
  .name {
    font-weight: 600;
  }
  .company,
  .phone {
    font-size: 0.8rem;
    color: var(--ink-mute);
  }
  .del {
    background: transparent;
    border: 1px solid var(--line);
    color: var(--ink-mute);
    padding: 0 0.6rem;
    cursor: pointer;
  }
  .del:hover {
    border-color: #ff6b6b;
    color: #ff6b6b;
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
  .link.danger:hover {
    color: #ff6b6b;
  }
</style>

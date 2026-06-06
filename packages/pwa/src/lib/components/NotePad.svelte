<script lang="ts">
  /**
   * <NotePad />
   *
   * Sketch gallery — each sketch is a records.type='sketch' row
   * whose image lives in Supabase Storage at
   * `notepad/<user_id>/<record_id>.png`, and whose body is filled
   * in by the process-sketch Edge Function calling a vision model
   * with the user's BYOK key.
   *
   * PWA is read-only: sketches arrive via vellum-sync notepad push
   * from the user's Palm NpadDB. No browser upload — the platform
   * only displays + AI-transcribes what the Palm produced.
   */
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';

  interface SketchMeta {
    image_path?: string;
    palm_title?: string;
    palm_modified_at?: string;
    palm_alarm_at?: string | null;
    palm_record_uid?: string;
  }

  interface Sketch {
    id: string;
    user_id: string;
    type: 'sketch';
    body: string | null;
    ai_status: string | null;
    ai_error: string | null;
    metadata: SketchMeta | null;
    created_at: string;
    updated_at: string;
  }

  // Public-bucket URL pattern.
  function imageUrl(metadata: SketchMeta | null): string | null {
    const path = metadata?.image_path;
    if (!path) return null;
    const { data } = supabase.storage.from('notepad').getPublicUrl(path);
    return data.publicUrl;
  }

  let sketches = $state<Sketch[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  // Detail view
  let activeId = $state<string | null>(null);
  let editingTitle = $state(false);
  let titleDraft = $state('');

  async function load() {
    if (!authState.userId) return;
    loading = true;
    loadError = null;
    const { data, error } = await supabase
      .from('records')
      .select('*')
      .eq('type', 'sketch')
      .is('deleted_at', null)
      .order('created_at', { ascending: false });
    loading = false;
    if (error) {
      loadError = error.message;
      return;
    }
    sketches = (data ?? []) as Sketch[];
  }

  // Upload paths removed in v0.5 — the Palm Note Pad is the only
  // source of truth; the platform just displays what HotSync brings
  // in via vellum-sync notepad push.

  async function deleteSketch(s: Sketch) {
    if (!confirm(`Delete sketch "${s.metadata?.palm_title ?? '(untitled)'}"?`)) return;
    const path = s.metadata?.image_path;
    if (path) {
      // Best-effort — record soft-delete is the source of truth
      await supabase.storage.from('notepad').remove([path]);
    }
    const { error } = await supabase
      .from('records')
      .update({ deleted_at: new Date().toISOString() })
      .eq('id', s.id);
    if (error) {
      alert(error.message);
      return;
    }
    if (activeId === s.id) activeId = null;
    await load();
  }

  async function reprocess(s: Sketch) {
    // Re-trigger vision by resetting ai_status to NULL then bouncing
    // back to pending. The webhook fires on INSERT only, so we have
    // to nudge a fresh INSERT — easiest: leave a comment for the next
    // iteration. For now, just show user can edit body manually.
    const newBody = prompt(
      'AI failed. Type the transcription manually, or leave empty to retry vision:',
      s.body ?? '',
    );
    if (newBody == null) return;
    if (newBody.trim() === '') {
      // mark as pending again — but webhook won't refire on UPDATE.
      // Best path: delete + re-upload. For now, mark error -> pending so
      // the user can see it was attempted to retry.
      await supabase
        .from('records')
        .update({ ai_status: 'pending', ai_error: null })
        .eq('id', s.id);
      alert('Marked pending. To trigger a fresh vision pass, delete this sketch and re-upload.');
    } else {
      await supabase
        .from('records')
        .update({ body: newBody, ai_status: 'done', ai_error: null })
        .eq('id', s.id);
    }
    await load();
  }

  function openDetail(s: Sketch) {
    activeId = s.id;
    editingTitle = false;
    titleDraft = s.metadata?.palm_title ?? '';
  }

  function closeDetail() {
    activeId = null;
    editingTitle = false;
  }

  async function saveTitle(s: Sketch) {
    const meta = { ...(s.metadata ?? {}), palm_title: titleDraft.trim() || 'untitled' };
    const { error } = await supabase
      .from('records')
      .update({ metadata: meta })
      .eq('id', s.id);
    if (error) {
      alert(error.message);
      return;
    }
    editingTitle = false;
    await load();
  }

  function fmtTime(s: string): string {
    return new Date(s).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  const activeSketch = $derived(activeId ? (sketches.find((s) => s.id === activeId) ?? null) : null);

  $effect(() => {
    if (authState.phase === 'ready') void load();
  });

  $effect(() => {
    const channel = supabase
      .channel('sketch-all')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'records' },
        async (payload) => {
          const row = (payload.new ?? payload.old) as { type?: string };
          if (row?.type !== 'sketch') return;
          await load();
        },
      )
      .subscribe();
    return () => {
      channel.unsubscribe();
    };
  });
</script>

<section class="notepad">
  <header class="head">
    <h2>note pad</h2>
    <p class="sub">
      Sketches arrive from your Palm's Note Pad on each HotSync. The
      platform AI transcribes any handwritten text and describes the
      drawing. Browser upload is intentionally off — the Palm is the
      only source of truth here.
    </p>
  </header>

  {#if loading}
    <p class="status">loading…</p>
  {:else if loadError}
    <p class="status error">{loadError}</p>
  {:else if sketches.length === 0}
    <p class="status">No sketches yet. Draw on your Palm Note Pad and HotSync to populate this gallery.</p>
  {:else}
    <ul class="grid">
      {#each sketches as s (s.id)}
        {@const img = imageUrl(s.metadata)}
        <li>
          <button
            type="button"
            class="card"
            onclick={() => openDetail(s)}
            aria-label="open sketch"
          >
            <div class="thumb">
              {#if img}
                <img src={img} alt={s.metadata?.palm_title ?? 'sketch'} loading="lazy" />
              {:else}
                <span class="no-img">no image</span>
              {/if}
            </div>
            <div class="meta">
              <div class="title">{s.metadata?.palm_title ?? '(untitled)'}</div>
              <div class="when">{fmtTime(s.created_at)}</div>
              {#if s.ai_status === 'pending' || s.ai_status === 'processing'}
                <div class="badge pending">[...] analyzing</div>
              {:else if s.ai_status === 'done'}
                <div class="snippet">{(s.body ?? '').slice(0, 80)}{(s.body ?? '').length > 80 ? '…' : ''}</div>
              {:else if s.ai_status === 'error'}
                <div class="badge errored">[err] {s.ai_error ?? 'failed'}</div>
              {/if}
            </div>
          </button>
        </li>
      {/each}
    </ul>
  {/if}

  {#if activeSketch}
    {@const img = imageUrl(activeSketch.metadata)}
    <div
      class="modal-bg"
      onclick={closeDetail}
      onkeydown={(e) => e.key === 'Escape' && closeDetail()}
      role="button"
      tabindex="-1"
      aria-label="close"
    >
      <article class="modal" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.stopPropagation()} role="dialog" tabindex="0" aria-modal="true">
        <header class="modal-h">
          {#if editingTitle}
            <input bind:value={titleDraft} maxlength="64" />
            <button class="link" onclick={() => saveTitle(activeSketch!)}>save</button>
            <button class="link" onclick={() => (editingTitle = false)}>cancel</button>
          {:else}
            <h3>{activeSketch.metadata?.palm_title ?? '(untitled)'}</h3>
            <button class="link" onclick={() => (editingTitle = true)}>rename</button>
          {/if}
          <button class="close" onclick={closeDetail} aria-label="close">×</button>
        </header>

        <div class="modal-body">
          {#if img}
            <img class="full" src={img} alt={activeSketch.metadata?.palm_title ?? 'sketch'} />
          {/if}

          <div class="ai-area">
            <div class="ai-h">AI transcription</div>
            {#if activeSketch.ai_status === 'pending' || activeSketch.ai_status === 'processing'}
              <p class="pending">[...] vision model is reading your sketch</p>
            {:else if activeSketch.ai_status === 'done'}
              <pre class="extracted">{activeSketch.body || '(blank)'}</pre>
            {:else if activeSketch.ai_status === 'error'}
              <p class="errored">[err] {activeSketch.ai_error ?? 'failed'}</p>
              <button class="link" onclick={() => reprocess(activeSketch!)}>retry / set manually</button>
            {:else}
              <p class="pending">queued…</p>
            {/if}
          </div>

          <footer class="modal-foot">
            <div class="when">created {fmtTime(activeSketch.created_at)}</div>
            <button class="del" onclick={() => deleteSketch(activeSketch!)}>delete</button>
          </footer>
        </div>
      </article>
    </div>
  {/if}
</section>

<style>
  .notepad {
    max-width: 1000px;
  }
  .head {
    margin-bottom: 1rem;
  }
  h2 {
    margin: 0 0 0.2rem;
    font-size: 1.1rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .sub {
    margin: 0;
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  code {
    background: var(--surface);
    padding: 0.05rem 0.3rem;
    border: 1px solid var(--line);
    color: var(--accent);
    font-size: 0.8rem;
  }

  .upload {
    background: var(--surface-lo);
    border: 1px dashed var(--line);
    padding: 1.2rem 1.1rem;
    margin-bottom: 1rem;
    text-align: center;
    cursor: pointer;
    color: var(--ink-mute);
    border-radius: 2px;
  }
  .upload:hover,
  .upload.drag-over {
    border-color: var(--accent);
    color: var(--accent);
    background: var(--surface);
  }
  .upload p {
    margin: 0 0 0.5rem;
    font-size: 0.9rem;
  }
  .title-input {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.4rem 0.55rem;
    font: inherit;
    font-size: 0.85rem;
    width: 100%;
    max-width: 400px;
  }

  .grid {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 0.7rem;
  }
  .card {
    width: 100%;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0;
    text-align: left;
    cursor: pointer;
    font: inherit;
    border-radius: 2px;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
  .card:hover {
    border-color: var(--accent);
  }
  .thumb {
    aspect-ratio: 1;
    background: #f0ead4;
    display: grid;
    place-items: center;
    overflow: hidden;
  }
  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }
  .no-img {
    color: var(--ink-mute);
    font-size: 0.8rem;
  }
  .meta {
    padding: 0.55rem 0.7rem;
    display: grid;
    gap: 0.2rem;
  }
  .title {
    font-weight: 600;
    font-size: 0.9rem;
  }
  .when {
    color: var(--ink-mute);
    font-size: 0.75rem;
  }
  .snippet {
    color: var(--ink-dim);
    font-size: 0.78rem;
    line-height: 1.35;
  }
  .badge {
    font-size: 0.75rem;
    padding: 0.1rem 0.4rem;
    border: 1px solid var(--line);
    justify-self: start;
  }
  .pending {
    color: #ffaf60;
  }
  .badge.pending {
    color: #ffaf60;
    border-color: #ffaf60;
  }
  .errored {
    color: #ff6b6b;
  }
  .badge.errored {
    color: #ff6b6b;
    border-color: #ff6b6b;
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

  .modal-bg {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.7);
    display: grid;
    place-items: center;
    padding: 1rem;
    z-index: 100;
  }
  .modal {
    background: var(--bg);
    border: 1px solid var(--line);
    max-width: 720px;
    width: 100%;
    max-height: 90vh;
    overflow: auto;
    display: grid;
    border-radius: 2px;
  }
  .modal-h {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0.8rem 1.1rem;
    border-bottom: 1px solid var(--line);
    gap: 0.5rem;
  }
  .modal-h h3 {
    margin: 0;
    font-size: 1rem;
    color: var(--ink);
    flex: 1;
  }
  .modal-h input {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.3rem 0.5rem;
    font: inherit;
    flex: 1;
  }
  .close {
    background: none;
    border: none;
    color: var(--ink-mute);
    font-size: 1.4rem;
    cursor: pointer;
    line-height: 1;
    padding: 0;
    width: 1.6rem;
    height: 1.6rem;
  }
  .close:hover {
    color: var(--accent);
  }
  .modal-body {
    padding: 1rem 1.1rem;
    display: grid;
    gap: 1rem;
  }
  .full {
    max-width: 100%;
    max-height: 60vh;
    object-fit: contain;
    background: #f0ead4;
    border: 1px solid var(--line);
    align-self: center;
  }
  .ai-area {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 0.8rem 1rem;
  }
  .ai-h {
    color: var(--accent);
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    margin-bottom: 0.4rem;
  }
  .extracted {
    margin: 0;
    font-family: inherit;
    font-size: 0.92rem;
    color: var(--ink);
    white-space: pre-wrap;
    word-break: break-word;
    line-height: 1.5;
  }
  .modal-foot {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-top: 0.5rem;
    border-top: 1px solid var(--line);
  }
  .del {
    background: none;
    border: 1px solid var(--line);
    color: var(--ink-mute);
    padding: 0.35rem 0.8rem;
    font: inherit;
    font-size: 0.8rem;
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
</style>

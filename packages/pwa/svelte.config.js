import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),

  kit: {
    adapter: adapter({
      pages: 'build',
      assets: 'build',
      fallback: 'index.html',     // SPA mode — everything routes through index
      precompress: false,
      strict: true,
    }),

    // We use Cloudflare/Vercel-style routing; no server-side anything.
    prerender: {
      handleHttpError: 'warn',
    },
  },
};

export default config;

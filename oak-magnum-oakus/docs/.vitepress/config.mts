import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(
  defineConfig({
    title: "The Magnum OAKus",
    description: "Production-grade recovery procedures for Apache Oak SegmentStore (TarMK)",
    
    // base: '/oak-magnum-oakus/', // Enable for GitHub Pages deployment
    cleanUrls: true,
    appearance: 'dark',
    ignoreDeadLinks: true,
    
    head: [
      ['link', { rel: 'icon', type: 'image/svg+xml', href: '/oak-magnum-oakus/oak-tree.svg' }],
      ['meta', { name: 'theme-color', content: '#0a1628' }],
      ['meta', { property: 'og:type', content: 'website' }],
      ['meta', { property: 'og:title', content: 'The Magnum OAKus' }],
      ['meta', { property: 'og:description', content: 'Production-grade recovery procedures for Apache Oak SegmentStore' }],
      ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ],

    themeConfig: {
      logo: '/oak-tree.svg',
      siteTitle: 'Magnum OAKus',
      
      nav: [
        { text: 'Home', link: '/' },
        { text: '🚨 Crisis', link: '/crisis/' },
        { text: '🏗️ Architecture', link: '/architecture/' },
        { text: '🛠️ Recovery', link: '/recovery/' },
        { text: '📋 Checkpoints', link: '/checkpoints/' },
        { text: '📚 Reference', link: '/reference/' },
      ],

      sidebar: {
        '/': [
          {
            text: '🚨 Emergency Response',
            collapsed: false,
            items: [
              { text: 'Crisis Checklist', link: '/crisis/' },
              { text: 'Quick Reference', link: '/crisis/quick-reference' },
              { text: 'Decision Tree', link: '/crisis/decision-tree' },
              { text: 'Identify Repo Type', link: '/crisis/identify-repo' },
            ]
          },
          {
            text: '🏗️ Architecture',
            collapsed: false,
            items: [
              { text: 'Overview', link: '/architecture/' },
              { text: 'Segments', link: '/architecture/segments' },
              { text: 'TAR Files', link: '/architecture/tar-files' },
              { text: 'Journal', link: '/architecture/journal' },
              { text: 'Generational GC', link: '/architecture/gc' },
            ]
          },
          {
            text: '🛠️ Recovery Operations',
            collapsed: false,
            items: [
              { text: 'Recovery Options', link: '/recovery/' },
              { text: 'oak-run check', link: '/recovery/check' },
              { text: 'Journal Recovery', link: '/recovery/journal' },
              { text: 'Surgical Removal', link: '/recovery/surgical' },
              { text: 'Compaction', link: '/recovery/compaction' },
              { text: 'Sidegrade', link: '/recovery/sidegrade' },
            ]
          },
          {
            text: '📋 Checkpoints',
            collapsed: false,
            items: [
              { text: 'Understanding Checkpoints', link: '/checkpoints/' },
              { text: 'Disk Bloat', link: '/checkpoints/disk-bloat' },
              { text: 'Async Indexing', link: '/checkpoints/async-indexing' },
              { text: 'Death Loop', link: '/checkpoints/death-loop' },
            ]
          },
          {
            text: '💾 DataStore',
            collapsed: true,
            items: [
              { text: 'DataStore Tools', link: '/datastore/' },
              { text: 'Consistency Check', link: '/datastore/consistency' },
              { text: 'Garbage Collection', link: '/datastore/gc' },
            ]
          },
          {
            text: '📚 Reference',
            collapsed: true,
            items: [
              { text: 'Command Reference', link: '/reference/' },
              { text: 'count-nodes', link: '/reference/count-nodes' },
              { text: 'Console Commands', link: '/reference/console' },
              { text: 'Troubleshooting', link: '/reference/troubleshooting' },
            ]
          },
        ],
      },

      socialLinks: [
        { icon: 'github', link: 'https://github.com/somarc/jackrabbit-oak' }
      ],

      footer: {
        message: 'Apache 2.0 Licensed',
        copyright: '🌳 Oak: The foundation of enterprise content'
      },

      search: {
        provider: 'local'
      },

      editLink: {
        pattern: 'https://github.com/somarc/jackrabbit-oak/edit/feature/blockchain-aem-poc/oak-magnum-oakus/docs/:path',
        text: 'Edit this page'
      },

      outline: {
        level: [2, 3],
        label: 'On this page'
      }
    },

    mermaid: {
      theme: 'dark',
      themeVariables: {
        primaryColor: '#4ade80',
        primaryTextColor: '#fff',
        primaryBorderColor: '#22c55e',
        lineColor: '#4ade80',
        secondaryColor: '#0a1628',
        tertiaryColor: '#0f2847',
        background: '#030712',
        mainBkg: '#0a1628',
        nodeBorder: '#4ade80',
      }
    },

    markdown: {
      lineNumbers: true,
      theme: {
        light: 'github-light',
        dark: 'github-dark'
      }
    }
  })
)

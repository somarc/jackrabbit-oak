import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import './custom.css'
import OakFlowGraph from './components/OakFlowGraph.vue'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    // Register global components for Oak visualizations
    app.component('OakFlowGraph', OakFlowGraph)
  }
} satisfies Theme

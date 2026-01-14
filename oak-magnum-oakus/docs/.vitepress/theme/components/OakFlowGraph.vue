<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'

interface Node {
  id: string
  type: string
  x: number
  y: number
  label: string
  icon: string
  color: string
  description?: string
  radius?: number
}

interface Edge {
  from: string
  to: string
  type: string
  color: string
  label?: string
  width?: number
}

const props = defineProps<{
  flow: 'segment-structure' | 'tar-lifecycle' | 'gc-cycle' | 'compaction' | 'checkpoint-pin' | 'recovery-decision' | 'journal-rebuild'
  height?: number
  interactive?: boolean
}>()

const emit = defineEmits(['nodeClick'])

const hoveredNode = ref<Node | null>(null)
const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const isAnimating = ref(false)
const animationPackets = ref<{ id: string; x: number; y: number; color: string }[]>([])

// Oak-specific node types
const NODE_TYPES: Record<string, { icon: string; color: string }> = {
  SEGMENT: { icon: '📦', color: '#3b82f6' },
  TAR_FILE: { icon: '🗃️', color: '#8b5cf6' },
  JOURNAL: { icon: '📜', color: '#f59e0b' },
  CHECKPOINT: { icon: '📍', color: '#ec4899' },
  ROOT: { icon: '🌳', color: '#4ade80' },
  NODE_RECORD: { icon: '📄', color: '#06b6d4' },
  BLOB_REF: { icon: '🔗', color: '#f97316' },
  GARBAGE: { icon: '🗑️', color: '#ef4444' },
  LIVE: { icon: '✅', color: '#22c55e' },
  GENERATION: { icon: '🔢', color: '#a855f7' },
  COMPACTION: { icon: '⚙️', color: '#6366f1' },
  REAPER: { icon: '🧹', color: '#64748b' },
  DECISION: { icon: '❓', color: '#eab308' },
  SUCCESS: { icon: '✅', color: '#22c55e' },
  FAILURE: { icon: '❌', color: '#ef4444' },
  BACKUP: { icon: '💾', color: '#0ea5e9' },
}

const EDGE_COLORS: Record<string, string> = {
  DATA: '#4ade80',
  REFERENCE: '#3b82f6',
  CONTROL: '#8b5cf6',
  DELETE: '#ef4444',
  COPY: '#22c55e',
}

const width = computed(() => {
  switch (props.flow) {
    case 'recovery-decision': return 900
    case 'gc-cycle': return 950
    case 'compaction': return 900
    default: return 850
  }
})

const height = computed(() => props.height || 400)

function initFlow() {
  switch (props.flow) {
    case 'segment-structure':
      initSegmentStructure()
      break
    case 'tar-lifecycle':
      initTarLifecycle()
      break
    case 'gc-cycle':
      initGCCycle()
      break
    case 'compaction':
      initCompaction()
      break
    case 'checkpoint-pin':
      initCheckpointPin()
      break
    case 'recovery-decision':
      initRecoveryDecision()
      break
    case 'journal-rebuild':
      initJournalRebuild()
      break
  }
}

function addNode(id: string, type: string, x: number, y: number, options: Partial<Node> = {}) {
  const nodeType = NODE_TYPES[type] || NODE_TYPES.SEGMENT
  nodes.value.push({
    id,
    type,
    x,
    y,
    label: options.label || type,
    icon: options.icon || nodeType.icon,
    color: options.color || nodeType.color,
    description: options.description,
    radius: options.radius || 28,
  })
}

function addEdge(from: string, to: string, type: string = 'DATA', options: Partial<Edge> = {}) {
  edges.value.push({
    from,
    to,
    type,
    color: options.color || EDGE_COLORS[type] || EDGE_COLORS.DATA,
    label: options.label,
    width: options.width || 2,
  })
}

// ============================================================================
// FLOW INITIALIZERS
// ============================================================================

function initSegmentStructure() {
  nodes.value = []
  edges.value = []
  
  // Segment internals
  addNode('segment', 'SEGMENT', 100, 200, { label: 'Segment', description: 'Up to 256KB immutable block', radius: 40 })
  addNode('uuid', 'NODE_RECORD', 250, 80, { label: 'UUID', description: 'Unique segment identifier' })
  addNode('nodes', 'NODE_RECORD', 250, 160, { label: 'Node Records', description: 'JCR node structure' })
  addNode('props', 'NODE_RECORD', 250, 240, { label: 'Properties', description: 'Node property values' })
  addNode('blobs', 'BLOB_REF', 250, 320, { label: 'Blob Refs', description: 'External binary references' })
  
  // References to other segments
  addNode('ref1', 'SEGMENT', 430, 120, { label: 'Segment B', description: 'Referenced segment' })
  addNode('ref2', 'SEGMENT', 430, 280, { label: 'Segment C', description: 'Referenced segment' })
  
  // TAR container
  addNode('tar', 'TAR_FILE', 620, 200, { label: 'data00001a.tar', description: 'TAR archive containing segments', radius: 35 })
  
  addEdge('segment', 'uuid', 'DATA', { label: 'contains' })
  addEdge('segment', 'nodes', 'DATA')
  addEdge('segment', 'props', 'DATA')
  addEdge('segment', 'blobs', 'DATA')
  addEdge('nodes', 'ref1', 'REFERENCE', { label: 'refs' })
  addEdge('props', 'ref2', 'REFERENCE', { label: 'refs' })
  addEdge('segment', 'tar', 'DATA', { label: 'stored in' })
  addEdge('ref1', 'tar', 'DATA')
  addEdge('ref2', 'tar', 'DATA')
}

function initTarLifecycle() {
  nodes.value = []
  edges.value = []
  
  // Initial state
  addNode('gen_a1', 'TAR_FILE', 80, 100, { label: 'data00000a.tar', description: 'Generation A - oldest' })
  addNode('gen_a2', 'TAR_FILE', 80, 200, { label: 'data00001a.tar', description: 'Generation A' })
  addNode('gen_a3', 'TAR_FILE', 80, 300, { label: 'data00002a.tar', description: 'Generation A - newest' })
  
  // Compaction
  addNode('compact', 'COMPACTION', 280, 200, { label: 'Compaction', description: 'Copy live segments to new gen' })
  
  // New generation
  addNode('gen_b1', 'TAR_FILE', 480, 150, { label: 'data00000b.tar', description: 'Generation B - compacted' })
  addNode('gen_b2', 'TAR_FILE', 480, 250, { label: 'data00001b.tar', description: 'Generation B - compacted' })
  
  // Cleanup
  addNode('cleanup', 'REAPER', 680, 200, { label: 'Cleanup', description: 'Delete old generation' })
  
  // Backup files
  addNode('bak', 'BACKUP', 680, 350, { label: '*.tar.bak', description: 'Backup before deletion' })
  
  addEdge('gen_a1', 'compact', 'DATA', { label: 'read' })
  addEdge('gen_a2', 'compact', 'DATA')
  addEdge('gen_a3', 'compact', 'DATA')
  addEdge('compact', 'gen_b1', 'COPY', { label: 'write' })
  addEdge('compact', 'gen_b2', 'COPY')
  addEdge('gen_b1', 'cleanup', 'CONTROL', { label: 'verify' })
  addEdge('gen_b2', 'cleanup', 'CONTROL')
  addEdge('cleanup', 'bak', 'DATA', { label: 'rename' })
}

function initGCCycle() {
  nodes.value = []
  edges.value = []
  
  // Start
  addNode('journal', 'JOURNAL', 70, 220, { label: 'Journal Head', description: 'Current repository state' })
  
  // Estimation phase
  addNode('estimate', 'COMPACTION', 220, 120, { label: 'Estimation', description: 'Calculate garbage amount' })
  addNode('threshold', 'DECISION', 220, 320, { label: 'Threshold?', description: 'Is garbage > 25%?' })
  
  // Compaction phase
  addNode('traverse', 'ROOT', 400, 120, { label: 'Tree Traversal', description: 'Walk from root' })
  addNode('mark', 'LIVE', 400, 220, { label: 'Mark Live', description: 'Mark reachable segments' })
  addNode('copy', 'COMPACTION', 400, 320, { label: 'Copy Live', description: 'Write to new generation' })
  
  // Cleanup phase
  addNode('new_gen', 'GENERATION', 580, 170, { label: 'New Generation', description: 'Compacted segments' })
  addNode('old_gen', 'GARBAGE', 580, 270, { label: 'Old Generation', description: 'To be deleted' })
  
  // Final
  addNode('cleanup', 'REAPER', 750, 220, { label: 'Cleanup', description: 'Delete old TAR files' })
  addNode('done', 'SUCCESS', 880, 220, { label: 'Complete', description: 'Space reclaimed' })
  
  addEdge('journal', 'estimate', 'DATA', { label: 'analyze' })
  addEdge('journal', 'threshold', 'CONTROL')
  addEdge('threshold', 'traverse', 'CONTROL', { label: 'yes' })
  addEdge('estimate', 'traverse', 'DATA')
  addEdge('traverse', 'mark', 'DATA', { label: 'walk' })
  addEdge('mark', 'copy', 'CONTROL', { label: 'live' })
  addEdge('copy', 'new_gen', 'COPY', { label: 'write' })
  addEdge('copy', 'old_gen', 'DELETE', { label: 'skip' })
  addEdge('new_gen', 'cleanup', 'CONTROL')
  addEdge('old_gen', 'cleanup', 'DELETE')
  addEdge('cleanup', 'done', 'DATA')
}

function initCompaction() {
  nodes.value = []
  edges.value = []
  
  // Before compaction
  addNode('head', 'JOURNAL', 70, 200, { label: 'HEAD', description: 'Current revision' })
  addNode('cp1', 'CHECKPOINT', 70, 320, { label: 'Checkpoint', description: 'Async indexer reference' })
  
  // Segments
  addNode('seg_live1', 'LIVE', 220, 100, { label: 'Live Seg 1', description: 'Referenced by HEAD' })
  addNode('seg_live2', 'LIVE', 220, 200, { label: 'Live Seg 2', description: 'Referenced by HEAD' })
  addNode('seg_dead1', 'GARBAGE', 220, 300, { label: 'Dead Seg 1', description: 'Not referenced' })
  addNode('seg_dead2', 'GARBAGE', 220, 400, { label: 'Dead Seg 2', description: 'Not referenced' })
  
  // Compaction process
  addNode('compact', 'COMPACTION', 420, 200, { label: 'Compaction', description: 'Copy live, skip dead' })
  
  // Result
  addNode('new_seg1', 'SEGMENT', 620, 150, { label: 'New Seg 1', description: 'Compacted segment' })
  addNode('new_seg2', 'SEGMENT', 620, 250, { label: 'New Seg 2', description: 'Compacted segment' })
  addNode('new_head', 'JOURNAL', 780, 200, { label: 'New HEAD', description: 'Updated revision' })
  
  addEdge('head', 'seg_live1', 'REFERENCE')
  addEdge('head', 'seg_live2', 'REFERENCE')
  addEdge('cp1', 'seg_live2', 'REFERENCE', { label: 'pins' })
  addEdge('seg_live1', 'compact', 'DATA', { label: 'copy' })
  addEdge('seg_live2', 'compact', 'DATA', { label: 'copy' })
  addEdge('seg_dead1', 'compact', 'DELETE', { label: 'skip' })
  addEdge('seg_dead2', 'compact', 'DELETE', { label: 'skip' })
  addEdge('compact', 'new_seg1', 'COPY')
  addEdge('compact', 'new_seg2', 'COPY')
  addEdge('new_seg1', 'new_head', 'REFERENCE')
  addEdge('new_seg2', 'new_head', 'REFERENCE')
}

function initCheckpointPin() {
  nodes.value = []
  edges.value = []
  
  // Current state
  addNode('head', 'JOURNAL', 100, 100, { label: 'HEAD (R100)', description: 'Current revision' })
  addNode('async', 'CHECKPOINT', 100, 250, { label: '/:async@async', description: 'Indexer checkpoint reference' })
  
  // Checkpoints
  addNode('cp_active', 'CHECKPOINT', 300, 250, { label: 'CP Active', description: 'Referenced by indexer' })
  addNode('cp_orphan1', 'CHECKPOINT', 300, 350, { label: 'CP Orphan 1', description: 'No longer referenced' })
  addNode('cp_orphan2', 'CHECKPOINT', 300, 450, { label: 'CP Orphan 2', description: 'No longer referenced' })
  
  // Segments pinned
  addNode('seg_current', 'SEGMENT', 520, 100, { label: 'Current Segs', description: 'HEAD references' })
  addNode('seg_pinned', 'SEGMENT', 520, 250, { label: 'Pinned Segs', description: 'Active CP references' })
  addNode('seg_orphan', 'GARBAGE', 520, 400, { label: 'Orphan Segs', description: 'Orphan CPs reference' })
  
  // TAR files
  addNode('tar_current', 'TAR_FILE', 720, 100, { label: 'Current TAR', description: 'Cannot delete' })
  addNode('tar_pinned', 'TAR_FILE', 720, 250, { label: 'Pinned TAR', description: 'Cannot delete' })
  addNode('tar_blocked', 'TAR_FILE', 720, 400, { label: 'Blocked TAR', description: 'Should delete but cannot' })
  
  addEdge('head', 'seg_current', 'REFERENCE')
  addEdge('async', 'cp_active', 'REFERENCE', { label: 'refs' })
  addEdge('cp_active', 'seg_pinned', 'REFERENCE', { label: 'pins' })
  addEdge('cp_orphan1', 'seg_orphan', 'REFERENCE', { label: 'pins' })
  addEdge('cp_orphan2', 'seg_orphan', 'REFERENCE', { label: 'pins' })
  addEdge('seg_current', 'tar_current', 'DATA')
  addEdge('seg_pinned', 'tar_pinned', 'DATA')
  addEdge('seg_orphan', 'tar_blocked', 'DATA', { label: 'blocks' })
}

function initRecoveryDecision() {
  nodes.value = []
  edges.value = []
  
  // Start
  addNode('start', 'DECISION', 80, 200, { label: 'oak-run check', description: 'Run consistency check' })
  
  // Decision points
  addNode('check_result', 'DECISION', 250, 200, { label: 'Check Result?', description: 'What did check find?' })
  addNode('good_rev', 'SUCCESS', 420, 100, { label: 'Good Revision', description: 'Recoverable state found' })
  addNode('no_good', 'FAILURE', 420, 200, { label: 'No Good Rev', description: 'Severely corrupted' })
  addNode('check_fail', 'FAILURE', 420, 300, { label: 'Check Failed', description: 'Repository bricked' })
  
  // Recovery options
  addNode('rollback', 'BACKUP', 600, 60, { label: 'Rollback', description: 'recover-journal (fast)' })
  addNode('surgical', 'COMPACTION', 600, 140, { label: 'Surgical', description: 'count-nodes + remove-nodes' })
  addNode('sidegrade', 'COMPACTION', 600, 220, { label: 'Sidegrade', description: 'oak-upgrade extract' })
  addNode('restore', 'BACKUP', 600, 320, { label: 'Restore Backup', description: 'Only option' })
  
  // Outcomes
  addNode('success', 'SUCCESS', 780, 140, { label: 'Recovered', description: 'Repository accessible' })
  addNode('partial', 'DECISION', 780, 220, { label: 'Partial Loss', description: 'Some data lost' })
  
  addEdge('start', 'check_result', 'DATA', { label: 'run' })
  addEdge('check_result', 'good_rev', 'DATA', { label: 'found' })
  addEdge('check_result', 'no_good', 'DATA', { label: 'none' })
  addEdge('check_result', 'check_fail', 'DELETE', { label: 'error' })
  addEdge('good_rev', 'rollback', 'CONTROL', { label: 'fast' })
  addEdge('good_rev', 'surgical', 'CONTROL', { label: 'preserve' })
  addEdge('no_good', 'sidegrade', 'CONTROL', { label: 'extract' })
  addEdge('check_fail', 'restore', 'DELETE', { label: 'only' })
  addEdge('rollback', 'success', 'COPY')
  addEdge('surgical', 'success', 'COPY')
  addEdge('sidegrade', 'partial', 'DATA')
  addEdge('restore', 'success', 'COPY')
}

function initJournalRebuild() {
  nodes.value = []
  edges.value = []
  
  // Corrupted state
  addNode('corrupt_journal', 'FAILURE', 80, 200, { label: 'Corrupt Journal', description: 'journal.log damaged' })
  
  // TAR files
  addNode('tar1', 'TAR_FILE', 250, 100, { label: 'data00000a.tar', description: 'Contains segments' })
  addNode('tar2', 'TAR_FILE', 250, 200, { label: 'data00001a.tar', description: 'Contains segments' })
  addNode('tar3', 'TAR_FILE', 250, 300, { label: 'data00002a.tar', description: 'Contains segments' })
  
  // Scan process
  addNode('scan', 'COMPACTION', 450, 200, { label: 'Scan TAR Files', description: 'Find valid root nodes' })
  
  // Found roots
  addNode('root1', 'ROOT', 620, 120, { label: 'Root R1', description: 'Valid revision found' })
  addNode('root2', 'ROOT', 620, 200, { label: 'Root R2', description: 'Valid revision found' })
  addNode('root3', 'ROOT', 620, 280, { label: 'Root R3', description: 'Most recent valid' })
  
  // New journal
  addNode('new_journal', 'JOURNAL', 780, 200, { label: 'New Journal', description: 'Rebuilt from R3' })
  
  addEdge('corrupt_journal', 'scan', 'DELETE', { label: 'ignore' })
  addEdge('tar1', 'scan', 'DATA', { label: 'read' })
  addEdge('tar2', 'scan', 'DATA')
  addEdge('tar3', 'scan', 'DATA')
  addEdge('scan', 'root1', 'DATA', { label: 'find' })
  addEdge('scan', 'root2', 'DATA')
  addEdge('scan', 'root3', 'DATA')
  addEdge('root3', 'new_journal', 'COPY', { label: 'write' })
}

function getEdgePath(edge: Edge): string {
  const fromNode = nodes.value.find(n => n.id === edge.from)
  const toNode = nodes.value.find(n => n.id === edge.to)
  if (!fromNode || !toNode) return ''
  
  const dx = toNode.x - fromNode.x
  const dy = toNode.y - fromNode.y
  const dist = Math.sqrt(dx * dx + dy * dy)
  
  const offsetFrom = (fromNode.radius || 28) + 5
  const offsetTo = (toNode.radius || 28) + 15
  
  const startX = fromNode.x + (dx / dist) * offsetFrom
  const startY = fromNode.y + (dy / dist) * offsetFrom
  const endX = toNode.x - (dx / dist) * offsetTo
  const endY = toNode.y - (dy / dist) * offsetTo
  
  const midX = (startX + endX) / 2
  const midY = (startY + endY) / 2
  const curvature = 0.12
  const ctrlX = midX - dy * curvature
  const ctrlY = midY + dx * curvature
  
  return `M ${startX} ${startY} Q ${ctrlX} ${ctrlY} ${endX} ${endY}`
}

function getEdgeLabelPosition(edge: Edge): { x: number; y: number } {
  const fromNode = nodes.value.find(n => n.id === edge.from)
  const toNode = nodes.value.find(n => n.id === edge.to)
  if (!fromNode || !toNode) return { x: 0, y: 0 }
  
  const dx = toNode.x - fromNode.x
  const dy = toNode.y - fromNode.y
  const midX = (fromNode.x + toNode.x) / 2
  const midY = (fromNode.y + toNode.y) / 2
  const curvature = 0.12
  
  return {
    x: midX - dy * curvature,
    y: midY + dx * curvature - 8
  }
}

function getEdgeStrokeDasharray(edge: Edge): string {
  if (edge.type === 'CONTROL') return '8 4'
  if (edge.type === 'DELETE') return '4 4'
  return 'none'
}

async function playAnimation() {
  if (isAnimating.value) return
  isAnimating.value = true
  
  // Animation sequences for each flow type
  const sequences: Record<string, { from: string; to: string; color: string }[][]> = {
    'segment-structure': [
      [{ from: 'segment', to: 'uuid', color: '#4ade80' }],
      [{ from: 'segment', to: 'nodes', color: '#4ade80' }, { from: 'segment', to: 'props', color: '#4ade80' }],
      [{ from: 'segment', to: 'blobs', color: '#4ade80' }],
      [{ from: 'nodes', to: 'ref1', color: '#3b82f6' }, { from: 'props', to: 'ref2', color: '#3b82f6' }],
      [{ from: 'segment', to: 'tar', color: '#4ade80' }],
    ],
    'gc-cycle': [
      [{ from: 'journal', to: 'estimate', color: '#4ade80' }],
      [{ from: 'journal', to: 'threshold', color: '#8b5cf6' }],
      [{ from: 'threshold', to: 'traverse', color: '#8b5cf6' }],
      [{ from: 'traverse', to: 'mark', color: '#4ade80' }],
      [{ from: 'mark', to: 'copy', color: '#8b5cf6' }],
      [{ from: 'copy', to: 'new_gen', color: '#22c55e' }, { from: 'copy', to: 'old_gen', color: '#ef4444' }],
      [{ from: 'new_gen', to: 'cleanup', color: '#8b5cf6' }, { from: 'old_gen', to: 'cleanup', color: '#ef4444' }],
      [{ from: 'cleanup', to: 'done', color: '#4ade80' }],
    ],
    'recovery-decision': [
      [{ from: 'start', to: 'check_result', color: '#4ade80' }],
      [{ from: 'check_result', to: 'good_rev', color: '#22c55e' }],
      [{ from: 'good_rev', to: 'rollback', color: '#8b5cf6' }],
      [{ from: 'rollback', to: 'success', color: '#22c55e' }],
    ],
  }
  
  const seq = sequences[props.flow] || []
  
  for (const step of seq) {
    await Promise.all(step.map(s => animatePacket(s.from, s.to, s.color)))
    await new Promise(r => setTimeout(r, 200))
  }
  
  isAnimating.value = false
}

async function animatePacket(fromId: string, toId: string, color: string): Promise<void> {
  const fromNode = nodes.value.find(n => n.id === fromId)
  const toNode = nodes.value.find(n => n.id === toId)
  if (!fromNode || !toNode) return
  
  const packetId = `${fromId}-${toId}-${Date.now()}`
  const duration = 600
  const startTime = performance.now()
  
  const dx = toNode.x - fromNode.x
  const dy = toNode.y - fromNode.y
  const dist = Math.sqrt(dx * dx + dy * dy)
  const offsetFrom = (fromNode.radius || 28) + 5
  const offsetTo = (toNode.radius || 28) + 15
  
  const startX = fromNode.x + (dx / dist) * offsetFrom
  const startY = fromNode.y + (dy / dist) * offsetFrom
  const endX = toNode.x - (dx / dist) * offsetTo
  const endY = toNode.y - (dy / dist) * offsetTo
  
  const midX = (startX + endX) / 2
  const midY = (startY + endY) / 2
  const curvature = 0.12
  const ctrlX = midX - dy * curvature
  const ctrlY = midY + dx * curvature
  
  return new Promise(resolve => {
    function animate() {
      const elapsed = performance.now() - startTime
      const t = Math.min(elapsed / duration, 1)
      
      const x = (1-t)*(1-t)*startX + 2*(1-t)*t*ctrlX + t*t*endX
      const y = (1-t)*(1-t)*startY + 2*(1-t)*t*ctrlY + t*t*endY
      
      const existing = animationPackets.value.find(p => p.id === packetId)
      if (existing) {
        existing.x = x
        existing.y = y
      } else {
        animationPackets.value.push({ id: packetId, x, y, color })
      }
      
      if (t < 1) {
        requestAnimationFrame(animate)
      } else {
        animationPackets.value = animationPackets.value.filter(p => p.id !== packetId)
        resolve()
      }
    }
    requestAnimationFrame(animate)
  })
}

onMounted(() => {
  initFlow()
})
</script>

<template>
  <div class="oak-flow-container">
    <div class="flow-controls">
      <button class="flow-btn play-btn" @click="playAnimation" :disabled="isAnimating">
        <span>{{ isAnimating ? '⏳' : '▶' }}</span>
        <span>{{ isAnimating ? 'Playing...' : 'Play Animation' }}</span>
      </button>
    </div>
    
    <div class="flow-wrapper">
      <svg 
        :viewBox="`0 0 ${width} ${height}`"
        class="flow-svg"
      >
        <defs>
          <marker id="oak-arrow" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
            <polygon points="0 0, 10 3.5, 0 7" fill="#4ade80" />
          </marker>
          <marker id="oak-arrow-red" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
            <polygon points="0 0, 10 3.5, 0 7" fill="#ef4444" />
          </marker>
          <filter id="oak-glow">
            <feGaussianBlur stdDeviation="3" result="coloredBlur"/>
            <feMerge>
              <feMergeNode in="coloredBlur"/>
              <feMergeNode in="SourceGraphic"/>
            </feMerge>
          </filter>
        </defs>
        
        <!-- Edges -->
        <g class="edges">
          <g v-for="edge in edges" :key="`${edge.from}-${edge.to}`">
            <path
              :d="getEdgePath(edge)"
              fill="none"
              :stroke="edge.color"
              :stroke-width="edge.width || 2"
              :stroke-dasharray="getEdgeStrokeDasharray(edge)"
              :marker-end="edge.type === 'DELETE' ? 'url(#oak-arrow-red)' : 'url(#oak-arrow)'"
              class="edge-path"
            />
            <text
              v-if="edge.label"
              :x="getEdgeLabelPosition(edge).x"
              :y="getEdgeLabelPosition(edge).y"
              text-anchor="middle"
              class="edge-label"
            >
              {{ edge.label }}
            </text>
          </g>
        </g>
        
        <!-- Nodes -->
        <g class="nodes">
          <g 
            v-for="node in nodes" 
            :key="node.id"
            :transform="`translate(${node.x}, ${node.y})`"
            class="node"
            :class="{ hovered: hoveredNode?.id === node.id }"
            @mouseenter="hoveredNode = node"
            @mouseleave="hoveredNode = null"
            @click="emit('nodeClick', node)"
          >
            <circle
              :r="(node.radius || 28) + 8"
              fill="none"
              :stroke="node.color"
              stroke-width="1"
              class="node-glow"
            />
            <circle
              :r="node.radius || 28"
              fill="#0a1628"
              :stroke="node.color"
              stroke-width="2"
              class="node-circle"
            />
            <text
              text-anchor="middle"
              dominant-baseline="central"
              font-size="18"
              class="node-icon"
            >
              {{ node.icon }}
            </text>
            <text
              text-anchor="middle"
              :y="(node.radius || 28) + 18"
              class="node-label"
            >
              {{ node.label }}
            </text>
          </g>
        </g>
        
        <!-- Animation packets -->
        <g class="packets">
          <circle
            v-for="packet in animationPackets"
            :key="packet.id"
            :cx="packet.x"
            :cy="packet.y"
            r="6"
            :fill="packet.color"
            filter="url(#oak-glow)"
            class="packet"
          />
        </g>
      </svg>
      
      <!-- Info panel -->
      <div v-if="hoveredNode && interactive !== false" class="info-panel">
        <div class="info-header">
          <span class="info-icon">{{ hoveredNode.icon }}</span>
          <span class="info-title">{{ hoveredNode.label }}</span>
        </div>
        <p v-if="hoveredNode.description" class="info-desc">{{ hoveredNode.description }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.oak-flow-container {
  margin: 1.5rem 0;
}

.flow-controls {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.flow-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  background: #0a1628;
  border: 1px solid rgba(74, 222, 128, 0.3);
  border-radius: 8px;
  color: #e6edf3;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.flow-btn:hover:not(:disabled) {
  background: #0f2847;
  border-color: #4ade80;
  transform: translateY(-2px);
}

.flow-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.flow-btn.play-btn {
  background: linear-gradient(135deg, #4ade80, #22c55e);
  border: none;
  color: #030712;
  font-weight: 600;
}

.flow-btn.play-btn:hover:not(:disabled) {
  box-shadow: 0 4px 20px rgba(74, 222, 128, 0.4);
}

.flow-wrapper {
  position: relative;
  background: #030712;
  border: 1px solid rgba(74, 222, 128, 0.2);
  border-radius: 12px;
  overflow: hidden;
}

.flow-svg {
  display: block;
  width: 100%;
  height: auto;
  background-image: 
    linear-gradient(rgba(74, 222, 128, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(74, 222, 128, 0.03) 1px, transparent 1px);
  background-size: 20px 20px;
}

.edge-path {
  transition: stroke-width 0.2s ease;
}

.edge-label {
  fill: rgba(255, 255, 255, 0.5);
  font-size: 10px;
  font-family: 'JetBrains Mono', monospace;
}

.node {
  cursor: pointer;
  transition: transform 0.2s ease;
}

.node:hover {
  transform: scale(1.05);
}

.node-glow {
  opacity: 0.2;
  transition: opacity 0.3s ease;
}

.node.hovered .node-glow {
  opacity: 0.5;
}

.node.hovered .node-circle {
  filter: url(#oak-glow);
}

.node-circle {
  transition: filter 0.2s ease;
}

.node-icon {
  pointer-events: none;
}

.node-label {
  fill: rgba(255, 255, 255, 0.7);
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  pointer-events: none;
}

.packet {
  pointer-events: none;
}

.info-panel {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 200px;
  background: #0f2847;
  border: 1px solid rgba(74, 222, 128, 0.3);
  border-radius: 10px;
  padding: 14px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  z-index: 10;
}

.info-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid rgba(74, 222, 128, 0.2);
}

.info-icon {
  font-size: 22px;
}

.info-title {
  font-weight: 600;
  font-size: 13px;
  color: #4ade80;
}

.info-desc {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  margin: 0;
  line-height: 1.5;
}

@media (max-width: 768px) {
  .info-panel {
    position: static;
    width: 100%;
    margin-top: 16px;
  }
}
</style>

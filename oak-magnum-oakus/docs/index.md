---
layout: home

hero:
  name: "The Magnum OAKus"
  text: "Production-grade recovery for Apache Oak"
  tagline: "Comprehensive guide to SegmentStore (TarMK) maintenance, corruption recovery, and operational excellence"
  image:
    src: /oak-tree.svg
    alt: Oak Tree
  actions:
    - theme: alt
      text: 🚨 IN CRISIS? START HERE
      link: /crisis/
    - theme: brand
      text: 📚 Learn the Architecture
      link: /architecture/
    - theme: alt
      text: View on GitHub
      link: https://github.com/somarc/jackrabbit-oak

features:
  - icon: 🚨
    title: Crisis Response
    details: Checkbox-driven checklist for repository corruption. Follow the boxes, zero fluff. Get your repository back online.
    link: /crisis/
  - icon: 🏗️
    title: Architecture Primer
    details: Understand why recovery works the way it does. Segments, TAR files, journal, and generational garbage collection.
    link: /architecture/
  - icon: 🛠️
    title: Recovery Operations
    details: oak-run check, journal recovery, surgical removal, compaction, and sidegrade procedures.
    link: /recovery/
  - icon: 📋
    title: Checkpoint Management
    details: Fix disk bloat, async indexing errors, and the dreaded "death loop" scenario.
    link: /checkpoints/
  - icon: 💾
    title: DataStore Tools
    details: Consistency checking, garbage collection, and blob management for FileDataStore, S3, and Azure.
    link: /datastore/
  - icon: 📚
    title: Command Reference
    details: Complete reference for oak-run commands, console operations, and troubleshooting guides.
    link: /reference/
---

<style>
:root {
  --vp-home-hero-name-color: transparent;
  --vp-home-hero-name-background: linear-gradient(135deg, #4ade80 0%, #22c55e 50%, #16a34a 100%);
}
</style>

## 🌳 What is The Magnum OAKus?

The definitive guide to Apache Oak repository operations, born from years of production incident response and deep architectural understanding.

**This guide will help you:**

- 🚨 **Recover from corruption** - Step-by-step procedures for every scenario
- 🏗️ **Understand the architecture** - Know why recovery works (or doesn't)
- ⚡ **Optimize performance** - Compaction, checkpoints, and disk management
- 🔍 **Diagnose issues** - Tools and techniques for root cause analysis

## ⚠️ Critical Reality Check

> **Repository corruption scenarios fall into three categories:**
>
> 1. **Recoverable** - Good revision found by `oak-run check` ✅
> 2. **Partially Recoverable** - Check completes but finds "no good revision" ⚠️
> 3. **Unrecoverable** - Check fails completely with SegmentNotFoundException ❌
>
> **If you have a recent, tested backup: RESTORE IT NOW.** Stop reading. Don't run diagnostics. Every minute spent "investigating" is wasted time when you have a guaranteed good state.

## 🚀 Quick Navigation

| Scenario | Start Here |
|----------|------------|
| **AEM won't start** | [Crisis Checklist](/crisis/) |
| **SegmentNotFoundException** | [Recovery Decision Tree](/crisis/decision-tree) |
| **Disk full / bloating** | [Checkpoint Management](/checkpoints/) |
| **Slow performance** | [Compaction Guide](/recovery/compaction) |
| **Learning the system** | [Architecture Primer](/architecture/) |

## 📖 About This Guide

Originally authored for Adobe Customer Support, The Magnum OAKus represents thousands of hours of production incident response distilled into actionable procedures.

**Primarily focused on:**
- SegmentStore (TarMK) - The most common AEM deployment
- DocumentNodeStore (MongoMK/RDB) references where applicable

**Philosophy:**
- **Backup-first** - The only guaranteed recovery method
- **Understand before acting** - Know why procedures work
- **Time-bounded decisions** - When in doubt, restore from backup

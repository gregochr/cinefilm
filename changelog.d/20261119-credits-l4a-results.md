### Docs — credits classifier plan: L4a's encoder choice and model A on the widened grid

Records L4a in `docs/engineering/credits-classifier-plan.md`: MobileNet v2-12 (fp32) is chosen as model B's encoder at 32 ms a frame, and CLIP ViT-B/32 is rejected at 294 ms. The embeddings estimates and model B's cost and memory figures are updated for its 1,000 dimensions. It also records model A evaluated again on the 42,000-point grid: the same final choice, and held-out figures within a few films of the earlier run.

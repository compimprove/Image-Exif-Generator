# SynthID engine dependencies

The installer contains CPython and the packages pinned in `requirements.txt`.
Their license files remain in the Python distribution and package metadata.
CPython uses the Python Software Foundation license; PyTorch uses BSD-style
licensing; Diffusers, Transformers, Hugging Face Hub, Accelerate and
ControlNet Auxiliary use Apache-2.0. See each installed distribution for its
complete notices and transitive dependencies.

The external implementation https://github.com/mertizci/noai-watermark is NOT
bundled or redistributed. On opt-in first use, the worker downloads the
SHA-256-verified archive of commit b642ae45d20eded52c96d570985eb4e3e427aac8
into the user's application-data directory. Upstream does not provide a
repository LICENSE. This download mechanism is not a grant of usage or
redistribution rights; distributing a product dependent on it still requires
resolving the upstream licensing terms.

Model repositories are pinned in `models.json` and downloaded from Hugging
Face. They retain their own terms, including the Stable Diffusion-derived
model's CreativeML Open RAIL-M terms. The application does not upload images.
The reverse-SynthID research detector is not included. Successful regeneration
is not independent verification that a SynthID watermark was removed.

Sources:
- https://github.com/guillaumemeyer/watermarks-remover
- https://github.com/mertizci/noai-watermark
- https://github.com/yepengliu/CtrlRegen
- https://huggingface.co/SG161222/Realistic_Vision_V4.0_noVAE
- https://huggingface.co/yepengliu/ctrlregen
- https://huggingface.co/facebook/dinov2-giant
- https://huggingface.co/stabilityai/sd-vae-ft-mse

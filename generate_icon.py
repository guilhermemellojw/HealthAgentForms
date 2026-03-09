
from PIL import Image, ImageDraw, ImageOps
import os

source_path = "/home/guilherme/.gemini/antigravity/brain/b3b48b6a-7fae-4119-93b6-64f06939ca9d/uploaded_image_1768407087142.jpg"
res_dir = "/home/guilherme/.gemini/antigravity/scratch/HealthAgentForms/app/src/main/res"

# Mipmap dimensions
mipmaps = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

def generate_icons():
    print(f"Loading {source_path}...")
    try:
        img = Image.open(source_path).convert("RGBA")
    except FileNotFoundError:
        print("Source image NOT FOUND!")
        return

    # 1. Update Foreground for Adaptive Icon
    # Adaptive foreground should be 108dp * density. 
    # For xxxhdpi (4x), that's 432x432.
    
    foreground_size = 432
    canvas = Image.new("RGBA", (foreground_size, foreground_size), (255, 255, 255, 0))
    
    # Resize image to fit comfortably in the center safe zone
    # User requested "occupy entire logo size" / "zoom in" even more (full bleed).
    # Previous scale was 400. Max canvas is 432.
    # To cover the entire background (432x432) and even bleed out slightly to avoid edges:
    # Let's try 460.
    target_scale = 460
    
    ratio = max(target_scale / img.width, target_scale / img.height)
    new_size = (int(img.width * ratio), int(img.height * ratio))
    img_resized = img.resize(new_size, Image.Resampling.LANCZOS)
    
    # Center crop/paste
    x = (foreground_size - new_size[0]) // 2
    y = (foreground_size - new_size[1]) // 2
    canvas.paste(img_resized, (x, y))
    
    foreground_path = os.path.join(res_dir, "drawable", "ic_launcher_foreground.png")
    os.makedirs(os.path.dirname(foreground_path), exist_ok=True)
    canvas.save(foreground_path, "PNG")
    print(f"Saved adaptive foreground: {foreground_path}")

    # 2. Generate Legacy Mipmaps
    # To "zoom in", we scale the image to be larger than the target size, then center crop.
    zoom_factor = 1.0 # Base fit
    # User wants even bigger. "full extent".
    # Let's upscale significantly.
    scale_multiplier = 1.6 # 60% zoom (Full Bleed)
    
    for folder, size in mipmaps.items():
        folder_path = os.path.join(res_dir, folder)
        os.makedirs(folder_path, exist_ok=True)
        
        # Resize logic: Scale larger than box then crop
        # Determine dimension to cover the box
        target_dim = int(size * scale_multiplier)
        
        icon = img.copy()
        ratio = max(target_dim / icon.width, target_dim / icon.height)
        new_icon_size = (int(icon.width * ratio), int(icon.height * ratio))
        icon = icon.resize(new_icon_size, Image.Resampling.LANCZOS)
        
        # Center Crop to size
        left = (icon.width - size) // 2
        top = (icon.height - size) // 2
        right = left + size
        bottom = top + size
        
        final_icon = icon.crop((left, top, right, bottom))
        
        final_icon.save(os.path.join(folder_path, "ic_launcher.png"))
        final_icon.save(os.path.join(folder_path, "ic_launcher_round.png"))
        print(f"Saved {folder}/ic_launcher.png")

if __name__ == "__main__":
    generate_icons()

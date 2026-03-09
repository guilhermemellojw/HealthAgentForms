import os
from PIL import Image, ImageOps, ImageDraw

SOURCE_IMAGE = "/home/guilherme/.gemini/antigravity/brain/e027332e-0e84-4037-a2da-507bf58c52eb/uploaded_image_1767122741677.png"
RES_DIR = "/home/guilherme/.gemini/antigravity/scratch/HealthAgentForms/app/src/main/res"

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

def remove_background_simple(img):
    img = img.convert("RGBA")
    datas = img.getdata()
    
    # Get corner color as reference
    bg_pixel = img.getpixel((0, 0))
    # If already transparent, return
    if bg_pixel[3] == 0:
        return img
        
    target_r, target_g, target_b = bg_pixel[:3]
    limit = 40 # Tolerance
    
    newData = []
    for item in datas:
        # If pixel is close to background color, make it transparent
        if (abs(item[0] - target_r) < limit and 
            abs(item[1] - target_g) < limit and 
            abs(item[2] - target_b) < limit):
            newData.append((255, 255, 255, 0))
        else:
            newData.append(item)
    
    img.putdata(newData)
    return img

def create_centered_icon(img, size):
    # 0. Attempt Background Removal
    img = remove_background_simple(img)

    # 1. Create transparent square canvas
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    
    # 2. Resize image to fit INSIDE the canvas (preserving aspect ratio)
    # Using contain ensures no cropping
    resized_img = ImageOps.contain(img, (size, size))
    
    # 3. Paste centered
    x = (size - resized_img.width) // 2
    y = (size - resized_img.height) // 2
    canvas.paste(resized_img, (x, y))
    
    return canvas

def main():
    if not os.path.exists(SOURCE_IMAGE):
        print(f"Error: Source image not found at {SOURCE_IMAGE}")
        return

    try:
        img = Image.open(SOURCE_IMAGE)
        img = img.convert("RGBA")
    except Exception as e:
        print(f"Error opening image: {e}")
        return

    for folder, size in SIZES.items():
        target_dir = os.path.join(RES_DIR, folder)
        if not os.path.exists(target_dir):
            os.makedirs(target_dir, exist_ok=True)

        # Create icon (centered, contained, no crop)
        icon = create_centered_icon(img, size)
        
        # Save as both launcher and round
        # Since the input is a custom shape (heart), we use the same image for both
        # rather than forcing a circle mask which might cut the heart.
        square_path = os.path.join(target_dir, "ic_launcher.png")
        icon.save(square_path, "PNG")
        
        round_path = os.path.join(target_dir, "ic_launcher_round.png")
        icon.save(round_path, "PNG")
        
        print(f"Saved icons in {folder} ({size}x{size})")

if __name__ == "__main__":
    main()

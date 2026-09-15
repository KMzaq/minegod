from pathlib import Path

import pdfplumber


pdf_path = Path(r"C:\Users\ADMIN\Desktop\markmar\.docx-review-actions\Mythic_TRPG_기획서_v0.1.pdf")
terms = (
    "AI의 역할 경계",
    "AI는 퀘스트",
    "공물 선호",
    "고급 상호작용",
    "AI 생성 퀘스트",
    "개입",
    "물리 현현",
    "대련 제안",
)

with pdfplumber.open(pdf_path) as pdf:
    print(f"PAGES={len(pdf.pages)}")
    for page_number, page in enumerate(pdf.pages, 1):
        text = page.extract_text() or ""
        hits = [term for term in terms if term in text]
        if hits:
            print(f"\n===== PAGE {page_number} | HITS: {', '.join(hits)} =====")
            print(text)

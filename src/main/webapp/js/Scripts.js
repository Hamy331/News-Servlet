$(".owl-carousel")
  .not(".all_newsslide")
  .owlCarousel({
    loop: true,
    margin: 10,
    nav: true,
    responsive: {
      0: { items: 1 },
      600: { items: 3 },
      1000: { items: 4 }
    }
  });

$(".all_newsslide").owlCarousel({
  loop: true,
  margin: 0,
  nav: true,
  items: 1
});

$(".dropdown").click(function () {
  var $menu = $(this).children(".mega-menu");

  // Toggle the visibility of mega menu on click
  if ($menu.is(":visible")) {
    $menu.stop(true, true).slideUp(200); // Ẩn mega menu
  } else {
    $(".mega-menu").stop(true, true).slideUp(200); // Ẩn tất cả các mega menu
    $menu.stop(true, true).slideDown(200); // Hiển thị mega menu
  }
});

// Đảm bảo các mega menu bị ẩn khi click bên ngoài menu
$(document).click(function (event) {
  if (!$(event.target).closest(".dropdown").length) {
    $(".mega-menu").slideUp(200); // Ẩn tất cả các mega menu khi click bên ngoài
  }
});

function showEditModal(title) {
  document.getElementById("editModal").style.display = "block";
}

function showRejectModal(title) {
  document.getElementById("rejectModal").style.display = "block";
}

function closeModal(modalId) {
  document.getElementById(modalId).style.display = "none";
}
